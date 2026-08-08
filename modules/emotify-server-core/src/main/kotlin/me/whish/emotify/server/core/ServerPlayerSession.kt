package me.whish.emotify.server.core

import kotlin.time.Duration
import me.whish.emotify.domain.CooldownGate
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiTransferRateLimits
import me.whish.emotify.domain.RemoteCustomEmojiRetention
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembler
import me.whish.emotify.wire.v1.CustomEmojiAssetAssemblyResult
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.MAX_SELECTION_RETRY_AFTER_MILLIS
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.domain.ProtocolNegotiation
import me.whish.emotify.domain.ProtocolNegotiator
import me.whish.emotify.domain.SELECTION_REJECTION_BURST_CAPACITY
import me.whish.emotify.domain.SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection

enum class ServerHandshakeFailure {
    INCOMPATIBLE_PROTOCOL,
    CHANGED_CLIENT_CAPABILITIES,
}

enum class ServerHandshakeTransition {
    SUPPORTED,
    UNSUPPORTED,
    NO_CHANGE,
}

sealed interface ServerHandshakeState {
    data object Pending : ServerHandshakeState

    data class Supported(
        val clientCapabilities: ProtocolCapabilities,
        val negotiated: ProtocolNegotiation.Supported,
    ) : ServerHandshakeState

    data class Unsupported(
        val reason: ServerHandshakeFailure,
        val initialClientCapabilities: ProtocolCapabilities,
    ) : ServerHandshakeState
}

sealed interface SelectionPreparation {
    data object Ready : SelectionPreparation

    data object Ignored : SelectionPreparation

    data class Rejected(
        val reason: SelectionRejectionReason,
        val retryAfterMillis: Int,
    ) : SelectionPreparation {
        init {
            require(retryAfterMillis in 0..MAX_SELECTION_RETRY_AFTER_MILLIS) {
                "Retry delay must be between 0 and $MAX_SELECTION_RETRY_AFTER_MILLIS ms: $retryAfterMillis"
            }
        }
    }
}

sealed interface CustomSelectionPreparation {
    data class Ready(
        val asset: CustomEmojiAsset,
        val losslessChunks: List<CustomEmojiAssetChunk>?,
    ) : CustomSelectionPreparation

    data object Ignored : CustomSelectionPreparation

    data class Rejected(
        val reason: SelectionRejectionReason,
        val retryAfterMillis: Int,
    ) : CustomSelectionPreparation {
        init {
            require(retryAfterMillis in 0..MAX_SELECTION_RETRY_AFTER_MILLIS) {
                "Retry delay must be between 0 and $MAX_SELECTION_RETRY_AFTER_MILLIS ms: $retryAfterMillis"
            }
        }
    }
}

class ServerPlayerSession(
    private val serverCapabilities: ProtocolCapabilities,
    selectionCooldown: Duration,
    private val timeSource: MonotonicTimeSource,
    private val featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
    private val customAssets: ServerCustomAssetStore = ServerCustomAssetStore(),
    private val customAssetIngressBudget: CustomAssetIngressBudget = CustomAssetIngressBudget(timeSource = timeSource),
) {
    private val selectionCooldown = CooldownGate(selectionCooldown, timeSource)
    private val rejectionResponses = TokenBucket(
        capacity = SELECTION_REJECTION_BURST_CAPACITY,
        refillTokensPerSecond = SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private val playResponses = TokenBucket(
        capacity = PLAY_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_REFILL_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private val customUploadBytes = TokenBucket(
        capacity = CUSTOM_UPLOAD_BURST_BYTES,
        refillTokensPerSecond = CUSTOM_UPLOAD_REFILL_BYTES_PER_SECOND,
        timeSource = timeSource,
    )
    private val customUploadStarts = TokenBucket(
        capacity = CUSTOM_UPLOAD_START_BURST,
        refillTokensPerSecond = CUSTOM_UPLOAD_START_REFILL_PER_SECOND,
        timeSource = timeSource,
    )
    private val customAssetDeliveries = TokenBucket(
        capacity = CustomEmojiTransferRateLimits.BURST_UNITS,
        refillTokensPerSecond = CustomEmojiTransferRateLimits.REFILL_UNITS_PER_SECOND,
        timeSource = timeSource,
    )
    private val customAssetAssembler = CustomEmojiAssetAssembler()
    private var customUploadAdmitted = false
    private var customUploadLease: CustomAssetIngressBudget.Lease? = null
    private val authorizedCustomAssets = object : LinkedHashMap<CustomEmojiId, Unit>(
        MAXIMUM_UPLOADED_CUSTOM_ASSETS * 4 / 3 + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CustomEmojiId, Unit>?): Boolean =
            size > MAXIMUM_UPLOADED_CUSTOM_ASSETS
    }
    private val rejectedCustomAssets = object : LinkedHashMap<CustomEmojiId, SelectionRejectionReason>(
        MAXIMUM_UPLOADED_CUSTOM_ASSETS * 4 / 3 + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CustomEmojiId, SelectionRejectionReason>?,
        ): Boolean = size > MAXIMUM_UPLOADED_CUSTOM_ASSETS
    }
    private val deliveredCustomAssets = RemoteCustomEmojiRetention()

    var handshakeState: ServerHandshakeState = ServerHandshakeState.Pending
        private set

    fun receiveClientHello(hello: ClientHello): ServerHandshakeTransition =
        when (val current = handshakeState) {
            ServerHandshakeState.Pending -> establish(hello)
            is ServerHandshakeState.Supported -> verifyRepeat(current, hello)
            is ServerHandshakeState.Unsupported -> ServerHandshakeTransition.NO_CHANGE
        }

    fun prepareSelection(
        emotionId: EmotionId,
        policy: ServerSelectionPolicy,
        player: PlayerSnapshot,
    ): SelectionPreparation {
        if (handshakeState !is ServerHandshakeState.Supported) {
            return SelectionPreparation.Ignored
        }
        if (!policy.catalog.contains(emotionId)) {
            return SelectionPreparation.Ignored
        }
        if (!policy.enabled) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_DISABLED, 0)
        }
        if (!policy.allowedEmotions.contains(emotionId)) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.EMOTION_DISABLED, 0)
        }
        if (!player.canPublish) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.PLAYER_STATE, 0)
        }

        val remaining = selectionCooldown.remaining()
        if (remaining.isPositive()) {
            val remainingNanos = remaining.inWholeNanoseconds
            val retryAfterMillis = ((remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L)
                .coerceAtMost(MAX_SELECTION_RETRY_AFTER_MILLIS.toLong())
                .toInt()
            return SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, retryAfterMillis)
        }

        return SelectionPreparation.Ready
    }

    fun prepareCustomSelection(
        selection: CustomEmotionSelection,
        policy: ServerSelectionPolicy,
        player: PlayerSnapshot,
    ): CustomSelectionPreparation {
        if (!supportsCustomEmojiSharing()) {
            return CustomSelectionPreparation.Ignored
        }
        if (!policy.enabled) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.SERVER_DISABLED, 0)
        }
        if (!policy.customEmojisEnabled) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED, 0)
        }
        if (!player.canPublish) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.PLAYER_STATE, 0)
        }

        val remaining = selectionCooldown.remaining()
        if (remaining.isPositive()) {
            val remainingNanos = remaining.inWholeNanoseconds
            val retryAfterMillis = ((remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L)
                .coerceAtMost(MAX_SELECTION_RETRY_AFTER_MILLIS.toLong())
                .toInt()
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, retryAfterMillis)
        }

        selection.asset?.let { asset ->
            if (asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
                return CustomSelectionPreparation.Rejected(SelectionRejectionReason.CUSTOM_ASSET_MISSING, 0)
            }
            customAssetRejection(asset, policy)?.let { reason ->
                return CustomSelectionPreparation.Rejected(reason, 0)
            }
            cacheUploadedCustomAsset(asset, null)
        }
        rejectedCustomAssets[selection.customEmojiId]?.let { reason ->
            return CustomSelectionPreparation.Rejected(reason, 0)
        }
        if (authorizedCustomAssets[selection.customEmojiId] == null) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.CUSTOM_ASSET_MISSING, 0)
        }
        val uploaded = customAssets.find(selection.customEmojiId)
            ?: return CustomSelectionPreparation.Rejected(SelectionRejectionReason.CUSTOM_ASSET_MISSING, 0)
        val asset = uploaded.asset
        if (!policy.allows(asset)) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE, 0)
        }
        if (!supportsCustomEmojiAsset(asset)) {
            return CustomSelectionPreparation.Rejected(SelectionRejectionReason.EMOTION_DISABLED, 0)
        }
        return CustomSelectionPreparation.Ready(asset, uploaded.losslessChunks)
    }

    fun supportsCustomEmojiSharing(): Boolean {
        val supported = handshakeState as? ServerHandshakeState.Supported ?: return false
        return supported.negotiated.features.contains(EmotifyProtocolFeatures.CUSTOM_EMOJI_SHARING)
    }

    fun supportsCustomEmojiAsset(asset: CustomEmojiAsset): Boolean =
        supportsCustomEmojiSharing() && (
            !asset.isAnimated || negotiatedFeaturesContain(EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING)
            ) && (
            asset.pixels.size <= LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE ||
                negotiatedFeaturesContain(EmotifyProtocolFeatures.LOSSLESS_CUSTOM_EMOJI_ASSETS)
            )

    fun receiveCustomAssetChunk(chunk: CustomEmojiAssetChunk, policy: ServerSelectionPolicy): Boolean {
        if (
            !policy.enabled ||
            !policy.customEmojisEnabled ||
            !negotiatedFeaturesContain(EmotifyProtocolFeatures.LOSSLESS_CUSTOM_EMOJI_ASSETS)
        ) {
            resetCustomUpload()
            return false
        }
        if (chunk.index == 0) {
            resetCustomUpload()
            customUploadAdmitted = customUploadStarts.tryConsume() &&
                customUploadBytes.tryConsume(chunk.totalBytes)
            if (customUploadAdmitted) {
                customUploadLease = customAssetIngressBudget.tryAcquire(chunk.totalBytes, ::expireCustomUpload)
                customUploadAdmitted = customUploadLease != null
            }
        }
        if (!customUploadAdmitted) {
            return false
        }
        if (customUploadLease?.isActive(timeSource.nowNanos()) != true) {
            resetCustomUpload()
            return false
        }
        return when (
            val result = customAssetAssembler.tryAcceptAssembly(
                chunk,
                timeSource.nowNanos() / NANOS_PER_MILLISECOND,
            )
        ) {
            CustomEmojiAssetAssemblyResult.Pending -> true
            is CustomEmojiAssetAssemblyResult.Completed -> {
                finishCustomUpload()
                val rejection = customAssetRejection(result.assembly.asset, policy)
                if (rejection == null) {
                    cacheUploadedCustomAsset(
                        result.assembly.asset,
                        result.assembly.takeIf { completed ->
                            completed.asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE
                        },
                    )
                    true
                } else {
                    rejectedCustomAssets[result.assembly.asset.id] = rejection
                    false
                }
            }
            is CustomEmojiAssetAssemblyResult.Rejected -> {
                finishCustomUpload()
                false
            }
        }
    }

    fun close() {
        resetCustomUpload()
    }

    fun needsCustomAsset(customEmojiId: CustomEmojiId): Boolean = !deliveredCustomAssets.contains(customEmojiId)

    fun markCustomAssetDelivered(asset: CustomEmojiAsset) {
        deliveredCustomAssets.retain(asset.id, asset.rawByteLength)
    }

    fun commitSelection() {
        check(selectionCooldown.tryAcquire()) { "Selection cooldown changed during main-thread validation" }
    }

    fun reconfigureSelectionCooldown(selectionCooldown: Duration) {
        this.selectionCooldown.reconfigure(selectionCooldown)
    }

    fun clearCustomAssetRejections() {
        rejectedCustomAssets.clear()
    }

    fun tryAdmitRejection(): Boolean = rejectionResponses.tryConsume()

    fun tryAdmitPlay(self: Boolean): Boolean =
        if (self) playResponses.tryConsume() else playResponses.tryConsumeRetaining(1)

    fun refundPlay() {
        playResponses.refundOne()
    }

    fun tryAdmitCustomAssetTransfer(units: Int): Boolean = customAssetDeliveries.tryConsume(units)

    fun refundCustomAssetTransfer(units: Int) {
        customAssetDeliveries.refund(units)
    }

    private fun establish(hello: ClientHello): ServerHandshakeTransition =
        when (val negotiated = ProtocolNegotiator.negotiate(serverCapabilities, hello.capabilities, featureRegistry)) {
            is ProtocolNegotiation.Supported -> {
                handshakeState = ServerHandshakeState.Supported(hello.capabilities, negotiated)
                ServerHandshakeTransition.SUPPORTED
            }
            is ProtocolNegotiation.Unsupported -> {
                handshakeState = ServerHandshakeState.Unsupported(
                    ServerHandshakeFailure.INCOMPATIBLE_PROTOCOL,
                    hello.capabilities,
                )
                ServerHandshakeTransition.UNSUPPORTED
            }
        }

    private fun verifyRepeat(
        current: ServerHandshakeState.Supported,
        hello: ClientHello,
    ): ServerHandshakeTransition {
        if (current.clientCapabilities == hello.capabilities) {
            return ServerHandshakeTransition.NO_CHANGE
        }

        handshakeState = ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.CHANGED_CLIENT_CAPABILITIES,
            current.clientCapabilities,
        )
        return ServerHandshakeTransition.UNSUPPORTED
    }

    private fun negotiatedFeaturesContain(feature: me.whish.emotify.domain.ProtocolFeature): Boolean {
        val supported = handshakeState as? ServerHandshakeState.Supported ?: return false
        return supported.negotiated.features.contains(feature)
    }

    private fun cacheUploadedCustomAsset(
        asset: CustomEmojiAsset,
        losslessAssembly: me.whish.emotify.wire.v1.CustomEmojiAssetAssembly?,
    ) {
        customAssets.put(asset, losslessAssembly)
        authorizedCustomAssets[asset.id] = Unit
        rejectedCustomAssets.remove(asset.id)
    }

    private fun customAssetRejection(
        asset: CustomEmojiAsset,
        policy: ServerSelectionPolicy,
    ): SelectionRejectionReason? = when {
        !policy.allows(asset) -> SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE
        !supportsCustomEmojiAsset(asset) -> SelectionRejectionReason.EMOTION_DISABLED
        else -> null
    }

    private fun finishCustomUpload() {
        customUploadAdmitted = false
        customUploadLease?.close()
        customUploadLease = null
    }

    private fun resetCustomUpload() {
        customAssetAssembler.reset()
        finishCustomUpload()
    }

    private fun expireCustomUpload() {
        customAssetAssembler.reset()
        customUploadAdmitted = false
        customUploadLease = null
    }

    companion object {
        private const val PLAY_BURST_CAPACITY = 32
        private const val PLAY_REFILL_TOKENS_PER_SECOND = 16
        private const val MAXIMUM_UPLOADED_CUSTOM_ASSETS = 128
        private const val CUSTOM_UPLOAD_BURST_BYTES = 512 * 1_024
        private const val CUSTOM_UPLOAD_REFILL_BYTES_PER_SECOND = 8 * 1_024
        private const val CUSTOM_UPLOAD_START_BURST = 2
        private const val CUSTOM_UPLOAD_START_REFILL_PER_SECOND = 1
        private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }

}
