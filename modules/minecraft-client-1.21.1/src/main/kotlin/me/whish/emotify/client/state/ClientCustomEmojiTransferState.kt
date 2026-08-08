package me.whish.emotify.client.state

import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiTransferRateLimits
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.domain.TokenBucket

class ClientCustomEmojiUploadTracker {
    private var activeConnectionId = 0L
    private val uploaded = HashSet<CustomEmojiId>()
    private val rejected = HashMap<CustomEmojiId, me.whish.emotify.domain.SelectionRejectionReason>()
    private var globalRejection: me.whish.emotify.domain.SelectionRejectionReason? = null

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        activeConnectionId = connectionId
        uploaded.clear()
        rejected.clear()
        globalRejection = null
    }

    fun prepare(connectionId: Long, asset: CustomEmojiAsset): CustomEmotionSelection? {
        if (connectionId != activeConnectionId) {
            return null
        }
        return CustomEmotionSelection(asset.id, asset.takeUnless { asset.id in uploaded })
    }

    fun requiresUpload(connectionId: Long, customEmojiId: CustomEmojiId): Boolean? =
        if (connectionId == activeConnectionId) customEmojiId !in uploaded else null

    fun markUploaded(connectionId: Long, customEmojiId: CustomEmojiId): Boolean =
        connectionId == activeConnectionId && uploaded.add(customEmojiId)

    fun forget(connectionId: Long, customEmojiId: CustomEmojiId): Boolean =
        connectionId == activeConnectionId && uploaded.remove(customEmojiId)

    fun reject(
        connectionId: Long,
        customEmojiId: CustomEmojiId,
        reason: me.whish.emotify.domain.SelectionRejectionReason,
    ): Boolean {
        require(reason == me.whish.emotify.domain.SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE) {
            "Only asset-specific custom emoji rejections can be cached: $reason"
        }
        if (connectionId != activeConnectionId) {
            return false
        }
        rejected[customEmojiId] = reason
        return true
    }

    fun rejectAll(connectionId: Long, reason: me.whish.emotify.domain.SelectionRejectionReason): Boolean {
        require(reason == me.whish.emotify.domain.SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED) {
            "Only global custom emoji rejections can be cached: $reason"
        }
        if (connectionId != activeConnectionId) {
            return false
        }
        globalRejection = reason
        return true
    }

    fun rejection(
        connectionId: Long,
        customEmojiId: CustomEmojiId,
    ): me.whish.emotify.domain.SelectionRejectionReason? =
        if (connectionId == activeConnectionId) globalRejection ?: rejected[customEmojiId] else null

    fun clearRejections(connectionId: Long): Boolean {
        if (connectionId != activeConnectionId) {
            return false
        }
        rejected.clear()
        globalRejection = null
        return true
    }

    fun disconnect(connectionId: Long) {
        if (connectionId != activeConnectionId) {
            return
        }
        activeConnectionId = 0L
        uploaded.clear()
        rejected.clear()
        globalRejection = null
    }
}

class ClientCustomEmojiAssetIngressGuard(
    timeSource: MonotonicTimeSource,
) {
    private val assets = TokenBucket(
        capacity = ASSET_BURST_CAPACITY,
        refillTokensPerSecond = ASSET_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private var activeConnectionId = 0L

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        activeConnectionId = connectionId
        assets.reset()
    }

    fun tryAdmit(connectionId: Long): Boolean = activeConnectionId == connectionId && assets.tryConsume()

    fun disconnect(connectionId: Long) {
        if (connectionId != activeConnectionId) {
            return
        }
        activeConnectionId = 0L
        assets.reset()
    }

    companion object {
        const val ASSET_BURST_CAPACITY = CustomEmojiTransferRateLimits.BURST_UNITS
        const val ASSET_TOKENS_PER_SECOND = CustomEmojiTransferRateLimits.REFILL_UNITS_PER_SECOND
    }
}
