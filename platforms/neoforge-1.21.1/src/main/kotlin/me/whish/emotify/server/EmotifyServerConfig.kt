package me.whish.emotify.server

import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.GlobalSelectionIngressLimits
import me.whish.emotify.server.core.ServerAudiencePolicy
import me.whish.emotify.server.core.ServerRuntimeSettings
import net.neoforged.neoforge.common.ModConfigSpec

object EmotifyServerConfig {
    private val defaultEmotionId = BuiltInEmotionManifest.definitions.first().id.value
    private val builder = ModConfigSpec.Builder()

    private val enabled = builder.define("enabled", true)
    private val cooldownMillis = builder.defineInRange(
        "cooldownMillis",
        ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS,
        ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS,
        10_000,
    )

    private val customEmojisEnabled: ModConfigSpec.BooleanValue
    private val maximumStaticCustomEmojiSize: ModConfigSpec.ConfigValue<Int>
    private val maximumAnimatedCustomEmojiSize: ModConfigSpec.ConfigValue<Int>
    private val allowedEmotionIds: ModConfigSpec.ConfigValue<out List<String>>
    private val deniedEmotionIds: ModConfigSpec.ConfigValue<out List<String>>
    private val broadcastRadiusBlocks: ModConfigSpec.DoubleValue
    private val maximumTrackingCandidates: ModConfigSpec.IntValue
    private val broadcastGlobalBurstCapacity: ModConfigSpec.IntValue
    private val broadcastGlobalRefillPerSecond: ModConfigSpec.IntValue
    private val broadcastRegionBurstCapacity: ModConfigSpec.IntValue
    private val broadcastRegionRefillPerSecond: ModConfigSpec.IntValue
    private val maximumBroadcastRegions: ModConfigSpec.IntValue
    private val maximumOutstandingSelections: ModConfigSpec.IntValue
    private val selectionGlobalBurstCapacity: ModConfigSpec.IntValue
    private val selectionGlobalRefillPerSecond: ModConfigSpec.IntValue

    init {
        builder.push("customEmojis")
        customEmojisEnabled = builder.define("enabled", true)
        maximumStaticCustomEmojiSize = builder.defineInList(
            "maximumStaticResolution",
            128,
            listOf(8, 16, 32, 64, 128),
        )
        maximumAnimatedCustomEmojiSize = builder.defineInList(
            "maximumAnimatedResolution",
            64,
            listOf(8, 16, 32, 64),
        )
        builder.pop()

        builder.push("emotions")
        allowedEmotionIds = builder.defineList(
            listOf("allow"),
            { emptyList<String>() },
            { defaultEmotionId },
            ::isValidEmotionId,
            ModConfigSpec.Range.of(0, ServerRuntimeSettings.MAXIMUM_EMOTION_FILTER_ENTRIES),
        )
        deniedEmotionIds = builder.defineList(
            listOf("deny"),
            { emptyList<String>() },
            { defaultEmotionId },
            ::isValidEmotionId,
            ModConfigSpec.Range.of(0, ServerRuntimeSettings.MAXIMUM_EMOTION_FILTER_ENTRIES),
        )
        builder.pop()

        builder.push("broadcast")
        broadcastRadiusBlocks = builder.defineInRange("radiusBlocks", 64.0, 1.0, 64.0)
        maximumTrackingCandidates = builder.defineInRange("maximumTrackingCandidates", 256, 1, 256)
        broadcastGlobalBurstCapacity = builder.defineInRange("globalBurstCapacity", 512, 1, 512)
        broadcastGlobalRefillPerSecond = builder.defineInRange("globalRefillPerSecond", 256, 1, 256)
        broadcastRegionBurstCapacity = builder.defineInRange("regionBurstCapacity", 32, 1, 32)
        broadcastRegionRefillPerSecond = builder.defineInRange("regionRefillPerSecond", 16, 1, 16)
        maximumBroadcastRegions = builder.defineInRange("maximumRegions", 4_096, 1, 4_096)
        builder.pop()

        builder.push("ingress")
        maximumOutstandingSelections = builder.defineInRange("maximumOutstandingSelections", 512, 1, 512)
        selectionGlobalBurstCapacity = builder.defineInRange("globalBurstCapacity", 1_024, 1, 1_024)
        selectionGlobalRefillPerSecond = builder.defineInRange("globalRefillPerSecond", 512, 1, 512)
        builder.pop()
    }

    val spec: ModConfigSpec = builder.build()

    fun snapshot(): ServerRuntimeSettings = ServerRuntimeSettings(
        enabled.get(),
        customEmojisEnabled.get(),
        maximumStaticCustomEmojiSize.get(),
        maximumAnimatedCustomEmojiSize.get(),
        cooldownMillis.get(),
        parseEmotionIds(allowedEmotionIds.get(), "emotions.allow"),
        parseEmotionIds(deniedEmotionIds.get(), "emotions.deny"),
        ServerAudiencePolicy(broadcastRadiusBlocks.get(), maximumTrackingCandidates.get()),
        AudienceBudgetLimits(
            broadcastGlobalBurstCapacity.get(),
            broadcastGlobalRefillPerSecond.get(),
            broadcastRegionBurstCapacity.get(),
            broadcastRegionRefillPerSecond.get(),
            maximumBroadcastRegions.get(),
        ),
        GlobalSelectionIngressLimits(
            maximumOutstandingSelections.get(),
            selectionGlobalBurstCapacity.get(),
            selectionGlobalRefillPerSecond.get(),
        ),
    )

    private fun parseEmotionIds(values: List<String>, path: String): Set<EmotionId> {
        val parsed = values.map { value ->
            requireNotNull(EmotionId.parse(value)) { "$path contains an invalid emotion ID: $value" }
        }
        require(parsed.toSet().size == parsed.size) { "$path contains a duplicate emotion ID" }
        return java.util.Set.copyOf(parsed)
    }

    private fun isValidEmotionId(value: Any?): Boolean = value is String && EmotionId.parse(value) != null
}
