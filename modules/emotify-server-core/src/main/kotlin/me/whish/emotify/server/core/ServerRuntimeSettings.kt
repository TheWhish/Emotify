package me.whish.emotify.server.core

import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.protocol.ServerHello

class ServerRuntimeSettings(
    val enabled: Boolean = true,
    val customEmojisEnabled: Boolean = true,
    val maximumStaticCustomEmojiSize: Int = CustomEmojiPixels.MAXIMUM_SIZE,
    val maximumAnimatedCustomEmojiSize: Int = CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE,
    val cooldownMillis: Int = EmotionAnimation.DURATION_MILLIS.toInt(),
    allowedEmotionIds: Set<EmotionId> = emptySet(),
    deniedEmotionIds: Set<EmotionId> = emptySet(),
    val audiencePolicy: ServerAudiencePolicy = ServerAudiencePolicy.DEFAULT,
    val audienceBudgetLimits: AudienceBudgetLimits = DEFAULT_AUDIENCE_BUDGET_LIMITS,
    val selectionIngressLimits: GlobalSelectionIngressLimits = DEFAULT_SELECTION_INGRESS_LIMITS,
) {
    val allowedEmotionIds: Set<EmotionId> = java.util.Set.copyOf(allowedEmotionIds)
    val deniedEmotionIds: Set<EmotionId> = java.util.Set.copyOf(deniedEmotionIds)

    init {
        require(CustomEmojiPixels.supports(maximumStaticCustomEmojiSize)) {
            "Maximum static custom emoji size is unsupported: $maximumStaticCustomEmojiSize"
        }
        require(CustomEmojiPixels.supports(maximumAnimatedCustomEmojiSize)) {
            "Maximum animated custom emoji size is unsupported: $maximumAnimatedCustomEmojiSize"
        }
        require(maximumAnimatedCustomEmojiSize <= CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE) {
            "Maximum animated custom emoji size exceeds the protocol limit: $maximumAnimatedCustomEmojiSize"
        }
        require(maximumAnimatedCustomEmojiSize <= maximumStaticCustomEmojiSize) {
            "Maximum animated custom emoji size cannot exceed the static limit"
        }
        require(cooldownMillis in MINIMUM_COOLDOWN_MILLIS..ServerHello.MAX_COOLDOWN_MILLIS) {
            "Server cooldown must be between $MINIMUM_COOLDOWN_MILLIS and ${ServerHello.MAX_COOLDOWN_MILLIS}: $cooldownMillis"
        }
        require(this.allowedEmotionIds.size <= MAXIMUM_EMOTION_FILTER_ENTRIES) {
            "Allowed emotion filter exceeds $MAXIMUM_EMOTION_FILTER_ENTRIES entries"
        }
        require(this.deniedEmotionIds.size <= MAXIMUM_EMOTION_FILTER_ENTRIES) {
            "Denied emotion filter exceeds $MAXIMUM_EMOTION_FILTER_ENTRIES entries"
        }
        require(this.allowedEmotionIds.intersect(this.deniedEmotionIds).isEmpty()) {
            "Emotion IDs cannot be present in both allow and deny filters"
        }
        require(audienceBudgetLimits.globalCapacity <= MAXIMUM_BROADCAST_GLOBAL_CAPACITY)
        require(audienceBudgetLimits.globalRefillTokensPerSecond <= MAXIMUM_BROADCAST_GLOBAL_REFILL_PER_SECOND)
        require(audienceBudgetLimits.regionCapacity <= MAXIMUM_BROADCAST_REGION_CAPACITY)
        require(audienceBudgetLimits.regionRefillTokensPerSecond <= MAXIMUM_BROADCAST_REGION_REFILL_PER_SECOND)
        require(audienceBudgetLimits.maximumRegions <= MAXIMUM_BROADCAST_REGIONS)
        require(selectionIngressLimits.maximumOutstanding <= MAXIMUM_OUTSTANDING_SELECTIONS)
        require(selectionIngressLimits.requestBurstCapacity <= MAXIMUM_SELECTION_BURST_CAPACITY)
        require(selectionIngressLimits.requestRefillTokensPerSecond <= MAXIMUM_SELECTION_REFILL_PER_SECOND)
    }

    fun configuration(baseHello: ServerHello): ServerRuntimeConfiguration {
        val catalog = baseHello.emotionCatalog
        require(allowedEmotionIds.all(catalog::contains)) { "Allowed emotion filter contains an unknown emotion ID" }
        require(deniedEmotionIds.all(catalog::contains)) { "Denied emotion filter contains an unknown emotion ID" }
        val allowFilter = if (allowedEmotionIds.isEmpty()) catalog.ids.toHashSet() else allowedEmotionIds
        val configuredCatalog = EmotionCatalog.of(
            catalog.ids.filter { emotionId -> emotionId in allowFilter && emotionId !in deniedEmotionIds },
        )
        val configuredHello = baseHello.copy(
            cooldownMillis = cooldownMillis,
            emotionCatalog = configuredCatalog,
        )
        return ServerRuntimeConfiguration(
            configuredHello,
            ServerSelectionPolicy(
                enabled,
                catalog,
                configuredCatalog,
                customEmojisEnabled,
                maximumStaticCustomEmojiSize,
                maximumAnimatedCustomEmojiSize,
            ),
            audiencePolicy,
        )
    }

    companion object {
        val MINIMUM_COOLDOWN_MILLIS = EmotionAnimation.DURATION_MILLIS.toInt()
        const val MAXIMUM_EMOTION_FILTER_ENTRIES = 64
        const val MAXIMUM_BROADCAST_GLOBAL_CAPACITY = 512
        const val MAXIMUM_BROADCAST_GLOBAL_REFILL_PER_SECOND = 256
        const val MAXIMUM_BROADCAST_REGION_CAPACITY = 32
        const val MAXIMUM_BROADCAST_REGION_REFILL_PER_SECOND = 16
        const val MAXIMUM_BROADCAST_REGIONS = 4_096
        const val MAXIMUM_OUTSTANDING_SELECTIONS = 512
        const val MAXIMUM_SELECTION_BURST_CAPACITY = 1_024
        const val MAXIMUM_SELECTION_REFILL_PER_SECOND = 512

        val DEFAULT_AUDIENCE_BUDGET_LIMITS = AudienceBudgetLimits(512, 256, 32, 16, 4_096)
        val DEFAULT_SELECTION_INGRESS_LIMITS = GlobalSelectionIngressLimits(512, 1_024, 512)
        val DISABLED = ServerRuntimeSettings(enabled = false, customEmojisEnabled = false)
    }
}
