package me.whish.emotify.paper.config

import java.util.concurrent.atomic.AtomicReference
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.GlobalSelectionIngressLimits
import me.whish.emotify.server.core.LegacyServerConfigurationMigration
import me.whish.emotify.server.core.ServerAudiencePolicy
import me.whish.emotify.server.core.ServerConfigurationVersion

data class PaperIngressConfiguration(
    val maximumQueuedMainThreadTasks: Int,
    val globalSelectionLimits: GlobalSelectionIngressLimits,
)

data class PaperBroadcastConfiguration(
    val audience: ServerAudiencePolicy,
    val budgetLimits: AudienceBudgetLimits,
)

data class PaperRuntimeConfig(
    val enabled: Boolean,
    val customEmojisEnabled: Boolean,
    val maximumStaticCustomEmojiSize: Int,
    val maximumAnimatedCustomEmojiSize: Int,
    val cooldownMillis: Int,
    val allowedEmotions: EmotionCatalog,
    val ingress: PaperIngressConfiguration,
    val broadcast: PaperBroadcastConfiguration,
)

class PaperConfigDocument(source: Map<String, Any?>) {
    private val values = HashMap<String, Any?>(source.size)

    init {
        source.forEach { (path, value) ->
            values[path] = if (value is Collection<*>) value.toList() else value
        }
    }

    val keys: Set<String>
        get() = java.util.Set.copyOf(values.keys)

    fun value(path: String): Any? = values[path]
}

sealed interface PaperConfigParseResult {
    data class Loaded(val config: PaperRuntimeConfig) : PaperConfigParseResult

    data class Invalid(val violations: List<String>) : PaperConfigParseResult {
        init {
            require(violations.isNotEmpty()) { "Invalid Paper configuration must contain a violation" }
        }
    }
}

class PaperConfigurationState(initial: PaperRuntimeConfig) {
    private val current = AtomicReference(initial)

    fun current(): PaperRuntimeConfig = current.get()

    fun replace(replacement: PaperRuntimeConfig): PaperRuntimeConfig = current.getAndSet(replacement)
}

object PaperRuntimeConfigParser {
    private const val CONFIG_VERSION = 1
    private const val ENABLED_PATH = "enabled"
    private const val DEFAULT_ENABLED = true
    private const val CUSTOM_EMOJIS_ENABLED_PATH = "custom-emojis.enabled"
    private const val MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_PATH = "custom-emojis.maximum-static-resolution"
    private const val MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_PATH = "custom-emojis.maximum-animated-resolution"
    private const val BROADCAST_RADIUS_PATH = "broadcast.radius-blocks"
    private const val DEFAULT_BROADCAST_RADIUS = 64.0
    private const val MAXIMUM_QUEUED_MAIN_THREAD_TASKS = 512
    private const val MAXIMUM_OUTSTANDING_SELECTIONS = 512
    private const val MAXIMUM_SELECTION_BURST_CAPACITY = 1_024
    private const val MAXIMUM_SELECTION_REFILL_PER_SECOND = 512
    private const val MAXIMUM_BROADCAST_GLOBAL_CAPACITY = 512
    private const val MAXIMUM_BROADCAST_GLOBAL_REFILL_PER_SECOND = 256
    private const val MAXIMUM_BROADCAST_REGION_CAPACITY = 32
    private const val MAXIMUM_BROADCAST_REGION_REFILL_PER_SECOND = 16
    private const val MAXIMUM_BROADCAST_REGIONS = 4_096
    private const val MAXIMUM_DOCUMENT_KEYS = 64
    private const val MAXIMUM_EMOTION_FILTER_ENTRIES = 64
    private const val MAXIMUM_VIOLATIONS = 32
    private const val MAXIMUM_VIOLATION_LENGTH = 256

    private val knownPaths = setOf(
        "config-version",
        ENABLED_PATH,
        CUSTOM_EMOJIS_ENABLED_PATH,
        MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_PATH,
        MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_PATH,
        "cooldown-millis",
        "emotions.allow",
        "emotions.deny",
        BROADCAST_RADIUS_PATH,
        "broadcast.maximum-tracking-candidates",
        "broadcast.global-burst-capacity",
        "broadcast.global-refill-per-second",
        "broadcast.region-burst-capacity",
        "broadcast.region-refill-per-second",
        "broadcast.maximum-regions",
        "ingress.maximum-queued-main-thread-tasks",
        "ingress.maximum-outstanding-selections",
        "ingress.global-burst-capacity",
        "ingress.global-refill-per-second",
    )

    fun parse(
        document: PaperConfigDocument,
        catalog: EmotionCatalog,
        configurationVersion: ServerConfigurationVersion = ServerConfigurationVersion.Current,
    ): PaperConfigParseResult {
        val violations = BoundedViolations()
        if (document.keys.size > MAXIMUM_DOCUMENT_KEYS) {
            violations += "Configuration contains more than $MAXIMUM_DOCUMENT_KEYS keys"
        }
        document.keys.asSequence().filterNot(knownPaths::contains).take(MAXIMUM_DOCUMENT_KEYS).sorted().forEach { path ->
            violations += "Unknown configuration key: $path"
        }

        val expectedVersion = when (configurationVersion) {
            ServerConfigurationVersion.Legacy -> 0
            ServerConfigurationVersion.Current -> CONFIG_VERSION
            is ServerConfigurationVersion.Future -> configurationVersion.value
        }
        val declaredVersion = readInt(document, "config-version", expectedVersion, violations)
        if (declaredVersion != expectedVersion) {
            violations += "config-version must be $expectedVersion: $declaredVersion"
        }
        val enabled = readEnabled(document, violations)
        val customEmojisEnabled = readBoolean(
            document,
            CUSTOM_EMOJIS_ENABLED_PATH,
            true,
            violations,
        )
        val maximumStaticCustomEmojiSize = readInt(
            document,
            MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_PATH,
            128,
            violations,
        ).validatedCustomEmojiSize(MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_PATH, 128, violations)
        val maximumAnimatedCustomEmojiSize = readInt(
            document,
            MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_PATH,
            64,
            violations,
        ).validatedCustomEmojiSize(MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_PATH, 64, violations)
        if (maximumAnimatedCustomEmojiSize > maximumStaticCustomEmojiSize) {
            violations += "$MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_PATH cannot exceed $MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_PATH"
        }
        val configuredCooldownMillis = readInt(
            document,
            "cooldown-millis",
            EmotionAnimation.DURATION_MILLIS.toInt(),
            violations,
        )
        val cooldownMillis = when (configurationVersion) {
            ServerConfigurationVersion.Legacy -> LegacyServerConfigurationMigration.cooldownMillis(configuredCooldownMillis)
            ServerConfigurationVersion.Current -> configuredCooldownMillis
            is ServerConfigurationVersion.Future -> configuredCooldownMillis
        }.validatedRange(
            "cooldown-millis",
            EmotionAnimation.DURATION_MILLIS.toInt(),
            ServerHello.MAX_COOLDOWN_MILLIS,
            violations,
        )
        val allow = readEmotionIds(document, "emotions.allow", catalog, violations)
        val deny = readEmotionIds(document, "emotions.deny", catalog, violations)
        val overlap = allow.intersect(deny)
        if (overlap.isNotEmpty()) {
            violations += "Emotion IDs cannot be present in both allow and deny: ${overlap.joinToString()}"
        }
        val radius = readBroadcastRadius(document, violations)
            .validatedRange(BROADCAST_RADIUS_PATH, 1.0, DEFAULT_BROADCAST_RADIUS, violations)
        val maximumTrackingCandidates = readInt(
            document,
            "broadcast.maximum-tracking-candidates",
            256,
            violations,
        ).validatedRange("broadcast.maximum-tracking-candidates", 1, 256, violations)
        val broadcastGlobalCapacity = readInt(
            document,
            "broadcast.global-burst-capacity",
            512,
            violations,
        ).validatedRange(
            "broadcast.global-burst-capacity",
            1,
            MAXIMUM_BROADCAST_GLOBAL_CAPACITY,
            violations,
        )
        val broadcastGlobalRefill = readInt(
            document,
            "broadcast.global-refill-per-second",
            256,
            violations,
        ).validatedRange(
            "broadcast.global-refill-per-second",
            1,
            MAXIMUM_BROADCAST_GLOBAL_REFILL_PER_SECOND,
            violations,
        )
        val broadcastRegionCapacity = readInt(
            document,
            "broadcast.region-burst-capacity",
            32,
            violations,
        ).validatedRange(
            "broadcast.region-burst-capacity",
            1,
            MAXIMUM_BROADCAST_REGION_CAPACITY,
            violations,
        )
        val broadcastRegionRefill = readInt(
            document,
            "broadcast.region-refill-per-second",
            16,
            violations,
        ).validatedRange(
            "broadcast.region-refill-per-second",
            1,
            MAXIMUM_BROADCAST_REGION_REFILL_PER_SECOND,
            violations,
        )
        val maximumRegions = readInt(
            document,
            "broadcast.maximum-regions",
            4_096,
            violations,
        ).validatedRange("broadcast.maximum-regions", 1, MAXIMUM_BROADCAST_REGIONS, violations)
        val maximumQueuedTasks = readInt(
            document,
            "ingress.maximum-queued-main-thread-tasks",
            512,
            violations,
        ).validatedRange(
            "ingress.maximum-queued-main-thread-tasks",
            1,
            MAXIMUM_QUEUED_MAIN_THREAD_TASKS,
            violations,
        )
        val maximumOutstandingSelections = readInt(
            document,
            "ingress.maximum-outstanding-selections",
            512,
            violations,
        ).validatedRange(
            "ingress.maximum-outstanding-selections",
            1,
            MAXIMUM_OUTSTANDING_SELECTIONS,
            violations,
        )
        val selectionBurstCapacity = readInt(
            document,
            "ingress.global-burst-capacity",
            1_024,
            violations,
        ).validatedRange(
            "ingress.global-burst-capacity",
            1,
            MAXIMUM_SELECTION_BURST_CAPACITY,
            violations,
        )
        val selectionRefill = readInt(
            document,
            "ingress.global-refill-per-second",
            512,
            violations,
        ).validatedRange(
            "ingress.global-refill-per-second",
            1,
            MAXIMUM_SELECTION_REFILL_PER_SECOND,
            violations,
        )

        if (violations.isNotEmpty()) {
            return PaperConfigParseResult.Invalid(violations.snapshot())
        }

        val allowFilter = if (allow.isEmpty()) catalog.ids.toHashSet() else allow
        val allowedEmotions = EmotionCatalog.of(catalog.ids.filter { id -> id in allowFilter && id !in deny })
        return PaperConfigParseResult.Loaded(
            PaperRuntimeConfig(
                enabled = enabled,
                customEmojisEnabled = customEmojisEnabled,
                maximumStaticCustomEmojiSize = maximumStaticCustomEmojiSize,
                maximumAnimatedCustomEmojiSize = maximumAnimatedCustomEmojiSize,
                cooldownMillis = cooldownMillis,
                allowedEmotions = allowedEmotions,
                ingress = PaperIngressConfiguration(
                    maximumQueuedTasks,
                    GlobalSelectionIngressLimits(
                        maximumOutstandingSelections,
                        selectionBurstCapacity,
                        selectionRefill,
                    ),
                ),
                broadcast = PaperBroadcastConfiguration(
                    ServerAudiencePolicy(radius, maximumTrackingCandidates),
                    AudienceBudgetLimits(
                        broadcastGlobalCapacity,
                        broadcastGlobalRefill,
                        broadcastRegionCapacity,
                        broadcastRegionRefill,
                        maximumRegions,
                    ),
                ),
            ),
        )
    }

    private fun readEnabled(
        document: PaperConfigDocument,
        violations: BoundedViolations,
    ): Boolean = readBoolean(document, ENABLED_PATH, DEFAULT_ENABLED, violations)

    private fun readBoolean(
        document: PaperConfigDocument,
        path: String,
        default: Boolean,
        violations: BoundedViolations,
    ): Boolean = when (val value = document.value(path)) {
        null -> default
        is Boolean -> value
        else -> {
            violations += "$path must be a boolean"
            default
        }
    }

    private fun readInt(
        document: PaperConfigDocument,
        path: String,
        default: Int,
        violations: BoundedViolations,
    ): Int = when (val value = document.value(path)) {
        null -> default
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else {
            violations += "$path must fit a 32-bit integer"
            default
        }
        else -> {
            violations += "$path must be an integer"
            default
        }
    }

    private fun readBroadcastRadius(
        document: PaperConfigDocument,
        violations: BoundedViolations,
    ): Double = when (val value = document.value(BROADCAST_RADIUS_PATH)) {
        null -> DEFAULT_BROADCAST_RADIUS
        is Number -> value.toDouble()
        else -> {
            violations += "$BROADCAST_RADIUS_PATH must be a number"
            DEFAULT_BROADCAST_RADIUS
        }
    }

    private fun readEmotionIds(
        document: PaperConfigDocument,
        path: String,
        catalog: EmotionCatalog,
        violations: BoundedViolations,
    ): Set<EmotionId> {
        val value = document.value(path) ?: return emptySet()
        if (value !is List<*>) {
            violations += "$path must be a list of emotion IDs"
            return emptySet()
        }
        if (value.size > MAXIMUM_EMOTION_FILTER_ENTRIES) {
            violations += "$path contains more than $MAXIMUM_EMOTION_FILTER_ENTRIES entries"
        }
        val result = LinkedHashSet<EmotionId>(value.size.coerceAtMost(MAXIMUM_EMOTION_FILTER_ENTRIES))
        value.take(MAXIMUM_EMOTION_FILTER_ENTRIES).forEachIndexed { index, entry ->
            if (entry !is String) {
                violations += "$path[$index] must be a string"
                return@forEachIndexed
            }
            val id = EmotionId.parse(entry)
            if (id == null) {
                violations += "$path[$index] contains an invalid emotion ID: $entry"
                return@forEachIndexed
            }
            if (!catalog.contains(id)) {
                violations += "$path[$index] contains an unknown emotion ID: $entry"
                return@forEachIndexed
            }
            if (!result.add(id)) {
                violations += "$path contains duplicate emotion ID: $entry"
            }
        }
        return result
    }

    private fun Int.validatedRange(
        path: String,
        minimum: Int,
        maximum: Int,
        violations: BoundedViolations,
    ): Int {
        if (this !in minimum..maximum) {
            violations += "$path must be between $minimum and $maximum: $this"
        }
        return this
    }

    private fun Int.validatedCustomEmojiSize(
        path: String,
        maximum: Int,
        violations: BoundedViolations,
    ): Int {
        if (this !in CUSTOM_EMOJI_SIZES || this > maximum) {
            violations += "$path must be one of ${CUSTOM_EMOJI_SIZES.filter { size -> size <= maximum }}: $this"
        }
        return this
    }

    private fun Double.validatedRange(
        path: String,
        minimum: Double,
        maximum: Double,
        violations: BoundedViolations,
    ): Double {
        if (!isFinite() || this < minimum || this > maximum) {
            violations += "$path must be finite and between $minimum and $maximum: $this"
        }
        return this
    }

    private class BoundedViolations {
        private val values = ArrayList<String>()

        operator fun plusAssign(message: String) {
            if (values.size < MAXIMUM_VIOLATIONS) {
                values += message.take(MAXIMUM_VIOLATION_LENGTH)
            }
        }

        fun isNotEmpty(): Boolean = values.isNotEmpty()

        fun snapshot(): List<String> = java.util.List.copyOf(values)
    }

    private val CUSTOM_EMOJI_SIZES = setOf(8, 16, 32, 64, 128)
}
