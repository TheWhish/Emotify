package me.whish.emotify.server.core

import kotlin.time.Duration
import me.whish.emotify.domain.CooldownGate
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiTransferRateLimits
import me.whish.emotify.domain.RemoteCustomEmojiRetention
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembler
import me.whish.emotify.wire.v1.CustomEmojiAssetVerificationResult
import me.whish.emotify.wire.v1.CustomEmojiEncodedAssemblyResult
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec
import me.whish.emotify.wire.v1.WireDecodeException
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
        val descriptor: CustomEmojiDescriptor,
    ) : CustomSelectionPreparation

    data object Ignored : CustomSelectionPreparation

    data object Deferred : CustomSelectionPreparation

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
    private val customUploadOwner = Any()
    private var customUploadGeneration = 0L
    private var customUploadState: CustomUploadState? = null
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
        val verifyingUpload = customUploadState as? CustomUploadState.Verifying
        if (verifyingUpload?.ticket?.assembly?.customEmojiId == selection.customEmojiId) {
            if (verifyingUpload.deferredSelection == null) {
                verifyingUpload.deferredSelection = selection
            }
            return CustomSelectionPreparation.Deferred
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
        return CustomSelectionPreparation.Ready(asset, uploaded.losslessChunks, selection.descriptor)
    }

    fun supportsCustomEmojiSharing(): Boolean {
        val supported = handshakeState as? ServerHandshakeState.Supported ?: return false
        return EmotifyProtocolFeatures.supportsCustomEmojiSharing(supported.negotiated.features)
    }

    fun supportsCustomEmojiAsset(asset: CustomEmojiAsset): Boolean =
        supportsCustomEmojiSharing() && (
            !asset.isAnimated || negotiatedFeaturesSupportAnimatedCustomEmojiSharing()
            ) && (
            asset.pixels.size <= LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE ||
                negotiatedFeaturesSupportLosslessCustomEmojiSharing()
            )

    fun receiveCustomAssetChunk(chunk: CustomEmojiAssetChunk, policy: ServerSelectionPolicy): Boolean =
        when (val preparation = prepareCustomAssetChunk(chunk, policy, permittedToUpload = true)) {
            SessionCustomAssetUploadPreparation.Pending -> true
            is SessionCustomAssetUploadPreparation.VerificationRequired -> when (
                completeCustomAssetVerification(
                    preparation.ticket,
                    preparation.ticket.assembly.tryVerify(),
                    policy,
                    permittedToUpload = true,
                )
            ) {
                is SessionCustomAssetUploadCommit.Accepted -> true
                is SessionCustomAssetUploadCommit.Rejected,
                SessionCustomAssetUploadCommit.Stale,
                -> false
            }
            is SessionCustomAssetUploadPreparation.Rejected -> false
        }

    internal fun prepareCustomAssetChunk(
        chunk: CustomEmojiAssetChunk,
        policy: ServerSelectionPolicy,
        permittedToUpload: Boolean,
    ): SessionCustomAssetUploadPreparation {
        uploadAdmissionRejection(policy, permittedToUpload)?.let { rejection ->
            resetCollectingCustomUpload()
            return SessionCustomAssetUploadPreparation.Rejected(rejection.first)
        }
        if (customUploadState is CustomUploadState.Verifying) {
            return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.BUSY)
        }
        if (chunk.index == 0) {
            val preflight = try {
                CustomEmojiLosslessCodec.preflightFirstChunk(chunk)
            } catch (_: WireDecodeException) {
                resetCollectingCustomUpload()
                return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.INVALID_TRANSFER)
            }
            resetCollectingCustomUpload()
            val generation = Math.incrementExact(customUploadGeneration)
            customUploadGeneration = generation
            if (!customUploadStarts.tryConsume() || !customUploadBytes.tryConsume(chunk.totalBytes)) {
                return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.RATE_LIMITED)
            }
            val lease = customAssetIngressBudget.tryAcquire(preflight) {
                expireCustomUpload(generation)
            } ?: return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.RATE_LIMITED)
            customUploadState = CustomUploadState.Collecting(
                generation,
                chunk.customEmojiId,
                lease,
            )
        }

        val collecting = customUploadState as? CustomUploadState.Collecting
            ?: return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.INVALID_TRANSFER)
        if (collecting.lease.isActive(timeSource.nowNanos()).not()) {
            resetCollectingCustomUpload()
            return SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.RATE_LIMITED)
        }
        return when (
            val result = customAssetAssembler.tryAcceptEncodedAssembly(
                chunk,
                timeSource.nowNanos() / NANOS_PER_MILLISECOND,
            )
        ) {
            CustomEmojiEncodedAssemblyResult.Pending -> SessionCustomAssetUploadPreparation.Pending
            is CustomEmojiEncodedAssemblyResult.Completed -> {
                val ticket = SessionCustomAssetVerificationTicket(
                    customUploadOwner,
                    collecting.generation,
                    collecting.lease,
                    result.assembly,
                )
                customUploadState = CustomUploadState.Verifying(ticket)
                SessionCustomAssetUploadPreparation.VerificationRequired(ticket)
            }
            is CustomEmojiEncodedAssemblyResult.Rejected -> {
                resetCollectingCustomUpload()
                SessionCustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.INVALID_TRANSFER)
            }
        }
    }

    internal fun completeCustomAssetVerification(
        ticket: SessionCustomAssetVerificationTicket,
        verification: CustomEmojiAssetVerificationResult,
        policy: ServerSelectionPolicy,
        permittedToUpload: Boolean,
    ): SessionCustomAssetUploadCommit {
        val verifying = customUploadState as? CustomUploadState.Verifying
        if (
            ticket.owner !== customUploadOwner ||
            verifying?.ticket !== ticket ||
            ticket.generation != customUploadGeneration
        ) {
            ticket.lease.close()
            return SessionCustomAssetUploadCommit.Stale
        }
        customUploadState = null
        val deferredSelection = verifying.deferredSelection
        return try {
            uploadAdmissionRejection(policy, permittedToUpload)?.let { rejection ->
                return SessionCustomAssetUploadCommit.Rejected(
                    rejection.first,
                    rejection.second,
                    deferredSelection,
                )
            }
            when (verification) {
                is CustomEmojiAssetVerificationResult.Rejected -> {
                    rejectedCustomAssets[ticket.assembly.customEmojiId] = SelectionRejectionReason.CUSTOM_ASSET_MISSING
                    SessionCustomAssetUploadCommit.Rejected(
                        CustomAssetUploadRejection.VERIFICATION_FAILED,
                        SelectionRejectionReason.CUSTOM_ASSET_MISSING,
                        deferredSelection,
                    )
                }
                is CustomEmojiAssetVerificationResult.Verified -> {
                    val assembly = verification.assembly
                    val rejection = customAssetRejection(assembly.asset, policy)
                    if (rejection != null) {
                        rejectedCustomAssets[assembly.asset.id] = rejection
                        SessionCustomAssetUploadCommit.Rejected(
                            CustomAssetUploadRejection.POLICY_REJECTED,
                            rejection,
                            deferredSelection,
                        )
                    } else {
                        cacheUploadedCustomAsset(
                            assembly.asset,
                            assembly.takeIf { completed ->
                                completed.asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE
                            },
                        )
                        SessionCustomAssetUploadCommit.Accepted(deferredSelection)
                    }
                }
            }
        } finally {
            ticket.lease.close()
        }
    }

    internal fun cancelCustomAssetVerification(ticket: SessionCustomAssetVerificationTicket): Boolean {
        val verifying = customUploadState as? CustomUploadState.Verifying ?: return false
        if (
            ticket.owner !== customUploadOwner ||
            verifying.ticket !== ticket ||
            ticket.generation != customUploadGeneration
        ) {
            return false
        }
        customUploadState = null
        ticket.lease.close()
        return true
    }

    fun close() {
        when (val upload = customUploadState) {
            is CustomUploadState.Collecting -> resetCollectingCustomUpload()
            is CustomUploadState.Verifying -> {
                customAssetAssembler.reset()
                customUploadState = null
                upload.ticket.lease.close()
            }
            null -> Unit
        }
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

    private fun negotiatedFeaturesSupportAnimatedCustomEmojiSharing(): Boolean {
        val supported = handshakeState as? ServerHandshakeState.Supported ?: return false
        return EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(supported.negotiated.features)
    }

    private fun negotiatedFeaturesSupportLosslessCustomEmojiSharing(): Boolean {
        val supported = handshakeState as? ServerHandshakeState.Supported ?: return false
        return EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(supported.negotiated.features)
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

    private fun resetCollectingCustomUpload() {
        customAssetAssembler.reset()
        val collecting = customUploadState as? CustomUploadState.Collecting ?: return
        customUploadState = null
        collecting.lease.close()
    }

    private fun expireCustomUpload(generation: Long) {
        val active = customUploadState ?: return
        if (active.generation != generation) {
            return
        }
        customAssetAssembler.reset()
        customUploadState = null
    }

    private fun uploadAdmissionRejection(
        policy: ServerSelectionPolicy,
        permittedToUpload: Boolean,
    ): Pair<CustomAssetUploadRejection, SelectionRejectionReason>? = when {
        !permittedToUpload -> CustomAssetUploadRejection.PERMISSION_DENIED to SelectionRejectionReason.PLAYER_STATE
        !policy.enabled -> CustomAssetUploadRejection.SERVER_DISABLED to SelectionRejectionReason.SERVER_DISABLED
        !policy.customEmojisEnabled ->
            CustomAssetUploadRejection.CUSTOM_EMOJIS_DISABLED to SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED
        !negotiatedFeaturesSupportLosslessCustomEmojiSharing() ->
            CustomAssetUploadRejection.PROTOCOL_UNSUPPORTED to SelectionRejectionReason.EMOTION_DISABLED
        else -> null
    }

    private sealed interface CustomUploadState {
        val generation: Long

        data class Collecting(
            override val generation: Long,
            val customEmojiId: CustomEmojiId,
            val lease: CustomAssetIngressBudget.Lease,
        ) : CustomUploadState

        class Verifying(
            val ticket: SessionCustomAssetVerificationTicket,
            var deferredSelection: CustomEmotionSelection? = null,
        ) : CustomUploadState {
            override val generation: Long
                get() = ticket.generation
        }
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
