package me.whish.emotify.fabric.config

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.GlobalSelectionIngressLimits
import me.whish.emotify.server.core.ServerAudiencePolicy
import me.whish.emotify.server.core.ServerRuntimeSettings
import net.fabricmc.loader.api.FabricLoader

data class FabricServerConfigSnapshot(
    val enabled: Boolean = true,
    val customEmojisEnabled: Boolean = true,
    val maximumStaticCustomEmojiSize: Int = 128,
    val maximumAnimatedCustomEmojiSize: Int = 64,
    val cooldownMillis: Int = 2_200,
    val allowedEmotionIds: Set<EmotionId> = emptySet(),
    val deniedEmotionIds: Set<EmotionId> = emptySet(),
    val broadcastRadiusBlocks: Double = 64.0,
    val maximumTrackingCandidates: Int = 256,
    val broadcastGlobalBurstCapacity: Int = 512,
    val broadcastGlobalRefillPerSecond: Int = 256,
    val broadcastRegionBurstCapacity: Int = 32,
    val broadcastRegionRefillPerSecond: Int = 16,
    val maximumBroadcastRegions: Int = 4_096,
    val maximumOutstandingSelections: Int = 512,
    val selectionGlobalBurstCapacity: Int = 1_024,
    val selectionGlobalRefillPerSecond: Int = 512,
) {
    init {
        runtimeSettings()
    }

    fun runtimeSettings(): ServerRuntimeSettings = ServerRuntimeSettings(
        enabled,
        customEmojisEnabled,
        maximumStaticCustomEmojiSize,
        maximumAnimatedCustomEmojiSize,
        cooldownMillis,
        allowedEmotionIds,
        deniedEmotionIds,
        ServerAudiencePolicy(broadcastRadiusBlocks, maximumTrackingCandidates),
        AudienceBudgetLimits(
            broadcastGlobalBurstCapacity,
            broadcastGlobalRefillPerSecond,
            broadcastRegionBurstCapacity,
            broadcastRegionRefillPerSecond,
            maximumBroadcastRegions,
        ),
        GlobalSelectionIngressLimits(
            maximumOutstandingSelections,
            selectionGlobalBurstCapacity,
            selectionGlobalRefillPerSecond,
        ),
    )
}

object FabricServerConfigCodec {
    fun decode(
        source: String,
        defaults: FabricServerConfigSnapshot = FabricServerConfigSnapshot(),
    ): FabricServerConfigSnapshot {
        var enabled = defaults.enabled
        var customEmojisEnabled = defaults.customEmojisEnabled
        var maximumStaticCustomEmojiSize = defaults.maximumStaticCustomEmojiSize
        var maximumAnimatedCustomEmojiSize = defaults.maximumAnimatedCustomEmojiSize
        var cooldownMillis = defaults.cooldownMillis
        var allowedEmotionIds = defaults.allowedEmotionIds
        var deniedEmotionIds = defaults.deniedEmotionIds
        var broadcastRadiusBlocks = defaults.broadcastRadiusBlocks
        var maximumTrackingCandidates = defaults.maximumTrackingCandidates
        var broadcastGlobalBurstCapacity = defaults.broadcastGlobalBurstCapacity
        var broadcastGlobalRefillPerSecond = defaults.broadcastGlobalRefillPerSecond
        var broadcastRegionBurstCapacity = defaults.broadcastRegionBurstCapacity
        var broadcastRegionRefillPerSecond = defaults.broadcastRegionRefillPerSecond
        var maximumBroadcastRegions = defaults.maximumBroadcastRegions
        var maximumOutstandingSelections = defaults.maximumOutstandingSelections
        var selectionGlobalBurstCapacity = defaults.selectionGlobalBurstCapacity
        var selectionGlobalRefillPerSecond = defaults.selectionGlobalRefillPerSecond
        val observedKeys = HashSet<String>(KNOWN_KEYS.size)
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid Emotify server config line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(observedKeys.add(key)) { "Duplicate Emotify server config key: $key" }
            when (key) {
                ENABLED_KEY -> enabled = value.toBooleanStrict()
                CUSTOM_EMOJIS_ENABLED_KEY -> customEmojisEnabled = value.toBooleanStrict()
                MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY -> maximumStaticCustomEmojiSize = value.toSupportedSize(128)
                MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY -> maximumAnimatedCustomEmojiSize = value.toSupportedSize(64)
                COOLDOWN_MILLIS_KEY -> cooldownMillis = value.toBoundedInt(
                    ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS,
                    10_000,
                    key,
                )
                ALLOWED_EMOTIONS_KEY -> allowedEmotionIds = value.toEmotionIds(key)
                DENIED_EMOTIONS_KEY -> deniedEmotionIds = value.toEmotionIds(key)
                BROADCAST_RADIUS_KEY -> broadcastRadiusBlocks = value.toBoundedDouble(1.0, 64.0, key)
                MAXIMUM_TRACKING_CANDIDATES_KEY -> maximumTrackingCandidates = value.toBoundedInt(1, 256, key)
                BROADCAST_GLOBAL_CAPACITY_KEY -> broadcastGlobalBurstCapacity = value.toBoundedInt(1, 512, key)
                BROADCAST_GLOBAL_REFILL_KEY -> broadcastGlobalRefillPerSecond = value.toBoundedInt(1, 256, key)
                BROADCAST_REGION_CAPACITY_KEY -> broadcastRegionBurstCapacity = value.toBoundedInt(1, 32, key)
                BROADCAST_REGION_REFILL_KEY -> broadcastRegionRefillPerSecond = value.toBoundedInt(1, 16, key)
                MAXIMUM_BROADCAST_REGIONS_KEY -> maximumBroadcastRegions = value.toBoundedInt(1, 4_096, key)
                MAXIMUM_OUTSTANDING_SELECTIONS_KEY -> maximumOutstandingSelections = value.toBoundedInt(1, 512, key)
                SELECTION_GLOBAL_CAPACITY_KEY -> selectionGlobalBurstCapacity = value.toBoundedInt(1, 1_024, key)
                SELECTION_GLOBAL_REFILL_KEY -> selectionGlobalRefillPerSecond = value.toBoundedInt(1, 512, key)
                else -> throw IllegalArgumentException("Unknown Emotify server config key: $key")
            }
        }
        require(maximumAnimatedCustomEmojiSize <= maximumStaticCustomEmojiSize) {
            "$MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY cannot exceed $MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY"
        }
        require(allowedEmotionIds.intersect(deniedEmotionIds).isEmpty()) {
            "Emotion IDs cannot be present in both allow and deny filters"
        }
        return FabricServerConfigSnapshot(
            enabled,
            customEmojisEnabled,
            maximumStaticCustomEmojiSize,
            maximumAnimatedCustomEmojiSize,
            cooldownMillis,
            allowedEmotionIds,
            deniedEmotionIds,
            broadcastRadiusBlocks,
            maximumTrackingCandidates,
            broadcastGlobalBurstCapacity,
            broadcastGlobalRefillPerSecond,
            broadcastRegionBurstCapacity,
            broadcastRegionRefillPerSecond,
            maximumBroadcastRegions,
            maximumOutstandingSelections,
            selectionGlobalBurstCapacity,
            selectionGlobalRefillPerSecond,
        )
    }

    fun encode(snapshot: FabricServerConfigSnapshot): String = buildString {
        appendSetting(ENABLED_KEY, snapshot.enabled)
        appendSetting(CUSTOM_EMOJIS_ENABLED_KEY, snapshot.customEmojisEnabled)
        appendSetting(MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY, snapshot.maximumStaticCustomEmojiSize)
        appendSetting(MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY, snapshot.maximumAnimatedCustomEmojiSize)
        appendSetting(COOLDOWN_MILLIS_KEY, snapshot.cooldownMillis)
        appendSetting(ALLOWED_EMOTIONS_KEY, snapshot.allowedEmotionIds.sortedBy(EmotionId::value).joinToString(",", transform = EmotionId::value))
        appendSetting(DENIED_EMOTIONS_KEY, snapshot.deniedEmotionIds.sortedBy(EmotionId::value).joinToString(",", transform = EmotionId::value))
        appendSetting(BROADCAST_RADIUS_KEY, snapshot.broadcastRadiusBlocks)
        appendSetting(MAXIMUM_TRACKING_CANDIDATES_KEY, snapshot.maximumTrackingCandidates)
        appendSetting(BROADCAST_GLOBAL_CAPACITY_KEY, snapshot.broadcastGlobalBurstCapacity)
        appendSetting(BROADCAST_GLOBAL_REFILL_KEY, snapshot.broadcastGlobalRefillPerSecond)
        appendSetting(BROADCAST_REGION_CAPACITY_KEY, snapshot.broadcastRegionBurstCapacity)
        appendSetting(BROADCAST_REGION_REFILL_KEY, snapshot.broadcastRegionRefillPerSecond)
        appendSetting(MAXIMUM_BROADCAST_REGIONS_KEY, snapshot.maximumBroadcastRegions)
        appendSetting(MAXIMUM_OUTSTANDING_SELECTIONS_KEY, snapshot.maximumOutstandingSelections)
        appendSetting(SELECTION_GLOBAL_CAPACITY_KEY, snapshot.selectionGlobalBurstCapacity)
        appendSetting(SELECTION_GLOBAL_REFILL_KEY, snapshot.selectionGlobalRefillPerSecond)
    }

    private fun StringBuilder.appendSetting(key: String, value: Any) {
        append(key)
        append('=')
        append(value)
        append('\n')
    }

    private fun String.toSupportedSize(maximum: Int): Int = toInt().also { size ->
        require(size in SUPPORTED_SIZES && size <= maximum) { "Unsupported custom emoji resolution: $size" }
    }

    private fun String.toBoundedInt(minimum: Int, maximum: Int, key: String): Int = toInt().also { value ->
        require(value in minimum..maximum) { "$key must be between $minimum and $maximum: $value" }
    }

    private fun String.toBoundedDouble(minimum: Double, maximum: Double, key: String): Double =
        toDouble().also { value ->
            require(value.isFinite() && value in minimum..maximum) {
                "$key must be finite and between $minimum and $maximum: $value"
            }
        }

    private fun String.toEmotionIds(key: String): Set<EmotionId> {
        if (isBlank()) {
            return emptySet()
        }
        val entries = split(',').map(String::trim)
        require(entries.size <= ServerRuntimeSettings.MAXIMUM_EMOTION_FILTER_ENTRIES) {
            "$key contains more than ${ServerRuntimeSettings.MAXIMUM_EMOTION_FILTER_ENTRIES} entries"
        }
        require(entries.none(String::isEmpty)) { "$key contains an empty emotion ID" }
        val ids = entries.map { value ->
            requireNotNull(EmotionId.parse(value)) { "$key contains an invalid emotion ID: $value" }
        }
        require(ids.toSet().size == ids.size) { "$key contains a duplicate emotion ID" }
        return java.util.Set.copyOf(ids)
    }

    private const val ENABLED_KEY = "enabled"
    private const val CUSTOM_EMOJIS_ENABLED_KEY = "customEmojis.enabled"
    private const val MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY = "customEmojis.maximumStaticResolution"
    private const val MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY = "customEmojis.maximumAnimatedResolution"
    private const val COOLDOWN_MILLIS_KEY = "cooldownMillis"
    private const val ALLOWED_EMOTIONS_KEY = "emotions.allow"
    private const val DENIED_EMOTIONS_KEY = "emotions.deny"
    private const val BROADCAST_RADIUS_KEY = "broadcast.radiusBlocks"
    private const val MAXIMUM_TRACKING_CANDIDATES_KEY = "broadcast.maximumTrackingCandidates"
    private const val BROADCAST_GLOBAL_CAPACITY_KEY = "broadcast.globalBurstCapacity"
    private const val BROADCAST_GLOBAL_REFILL_KEY = "broadcast.globalRefillPerSecond"
    private const val BROADCAST_REGION_CAPACITY_KEY = "broadcast.regionBurstCapacity"
    private const val BROADCAST_REGION_REFILL_KEY = "broadcast.regionRefillPerSecond"
    private const val MAXIMUM_BROADCAST_REGIONS_KEY = "broadcast.maximumRegions"
    private const val MAXIMUM_OUTSTANDING_SELECTIONS_KEY = "ingress.maximumOutstandingSelections"
    private const val SELECTION_GLOBAL_CAPACITY_KEY = "ingress.globalBurstCapacity"
    private const val SELECTION_GLOBAL_REFILL_KEY = "ingress.globalRefillPerSecond"
    private val SUPPORTED_SIZES = setOf(8, 16, 32, 64, 128)
    private val KNOWN_KEYS = setOf(
        ENABLED_KEY,
        CUSTOM_EMOJIS_ENABLED_KEY,
        MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY,
        MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY,
        COOLDOWN_MILLIS_KEY,
        ALLOWED_EMOTIONS_KEY,
        DENIED_EMOTIONS_KEY,
        BROADCAST_RADIUS_KEY,
        MAXIMUM_TRACKING_CANDIDATES_KEY,
        BROADCAST_GLOBAL_CAPACITY_KEY,
        BROADCAST_GLOBAL_REFILL_KEY,
        BROADCAST_REGION_CAPACITY_KEY,
        BROADCAST_REGION_REFILL_KEY,
        MAXIMUM_BROADCAST_REGIONS_KEY,
        MAXIMUM_OUTSTANDING_SELECTIONS_KEY,
        SELECTION_GLOBAL_CAPACITY_KEY,
        SELECTION_GLOBAL_REFILL_KEY,
    )
}

object FabricServerConfig {
    private val configPath = FabricLoader.getInstance().configDir.resolve("emotify-server.properties")

    @Volatile
    private var current: FabricServerConfigSnapshot? = null

    fun initialize() {
        current = try {
            if (Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
                    "Emotify server config is not a regular file: $configPath"
                }
                FabricServerConfigCodec.decode(readBoundedUtf8()).also(::persist)
            } else {
                FabricServerConfigSnapshot().also(::persist)
            }
        } catch (exception: Exception) {
            throw IllegalStateException("Failed to load Emotify server config from $configPath", exception)
        }
    }

    fun snapshot(): FabricServerConfigSnapshot = checkNotNull(current) {
        "Emotify Fabric server config has not been initialized"
    }

    private fun persist(snapshot: FabricServerConfigSnapshot) {
        FabricServerConfigPersistence.write(configPath, FabricServerConfigCodec.encode(snapshot))
    }

    private fun readBoundedUtf8(): String {
        val bytes = Files.newInputStream(configPath).use { input ->
            input.readNBytes(MAXIMUM_CONFIG_BYTES + 1)
        }
        require(bytes.size <= MAXIMUM_CONFIG_BYTES) {
            "Emotify server config exceeds $MAXIMUM_CONFIG_BYTES bytes"
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private const val MAXIMUM_CONFIG_BYTES = 16_384
}

internal object FabricServerConfigPersistence {
    fun write(path: Path, content: String) {
        val parent = checkNotNull(path.parent) { "Emotify config path has no parent: $path" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            )
            require(Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                "Emotify temporary config is not a regular file: $temporary"
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
