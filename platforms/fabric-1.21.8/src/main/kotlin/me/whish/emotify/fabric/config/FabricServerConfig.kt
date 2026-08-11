package me.whish.emotify.fabric.config

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.GlobalSelectionIngressLimits
import me.whish.emotify.server.core.LegacyServerConfigurationMigration
import me.whish.emotify.server.core.ServerAudiencePolicy
import me.whish.emotify.server.core.ServerConfigurationFileIO
import me.whish.emotify.server.core.ServerConfigurationSchema
import me.whish.emotify.server.core.ServerConfigurationVersion
import me.whish.emotify.server.core.ServerRuntimeSettings
import net.fabricmc.loader.api.FabricLoader

data class FabricServerConfigSnapshot(
    val enabled: Boolean = true,
    val customEmojisEnabled: Boolean = true,
    val maximumStaticCustomEmojiSize: Int = 128,
    val maximumAnimatedCustomEmojiSize: Int = 64,
    val cooldownMillis: Int = ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS,
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

sealed interface FabricServerConfigDecodeResult {
    data class Ready(
        val snapshot: FabricServerConfigSnapshot,
        val version: ServerConfigurationVersion,
    ) : FabricServerConfigDecodeResult

    data class Future(val version: Int) : FabricServerConfigDecodeResult
}

object FabricServerConfigCodec {
    fun decode(
        source: String,
        defaults: FabricServerConfigSnapshot = FabricServerConfigSnapshot(),
    ): FabricServerConfigDecodeResult {
        val version = ServerConfigurationSchema.classify(declaredVersion(source))
        return when (version) {
            is ServerConfigurationVersion.Future -> FabricServerConfigDecodeResult.Future(version.value)
            ServerConfigurationVersion.Current,
            ServerConfigurationVersion.Legacy,
            -> FabricServerConfigDecodeResult.Ready(decodeCompatible(source, defaults, version), version)
        }
    }

    fun decodeCompatible(
        source: String,
        defaults: FabricServerConfigSnapshot = FabricServerConfigSnapshot(),
        version: ServerConfigurationVersion = ServerConfigurationVersion.Current,
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
                CONFIG_VERSION_KEY -> value.toInt().also { version ->
                    require(version in ServerConfigurationSchema.LEGACY_VERSION..ServerConfigurationSchema.CURRENT_VERSION) {
                        "Unsupported compatible Emotify server config version: $version"
                    }
                }
                ENABLED_KEY -> enabled = value.toBooleanStrict()
                CUSTOM_EMOJIS_ENABLED_KEY -> customEmojisEnabled = value.toBooleanStrict()
                MAXIMUM_STATIC_CUSTOM_EMOJI_SIZE_KEY -> maximumStaticCustomEmojiSize = value.toSupportedSize(128)
                MAXIMUM_ANIMATED_CUSTOM_EMOJI_SIZE_KEY -> maximumAnimatedCustomEmojiSize = value.toSupportedSize(64)
                COOLDOWN_MILLIS_KEY -> cooldownMillis = value.toCooldownMillis(version, key)
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
        appendSetting(CONFIG_VERSION_KEY, ServerConfigurationSchema.CURRENT_VERSION)
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

    private fun String.toCooldownMillis(version: ServerConfigurationVersion, key: String): Int {
        val parsed = toInt()
        val compatible = when (version) {
            ServerConfigurationVersion.Legacy -> LegacyServerConfigurationMigration.cooldownMillis(parsed)
            ServerConfigurationVersion.Current -> parsed
            is ServerConfigurationVersion.Future -> error("Future server configuration must remain opaque")
        }
        require(compatible in ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS..10_000) {
            "$key must be between ${ServerRuntimeSettings.MINIMUM_COOLDOWN_MILLIS} and 10000: $parsed"
        }
        return compatible
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

    private fun declaredVersion(source: String): Int? {
        var declaredVersion: Int? = null
        source.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val separator = line.indexOf('=')
            if (separator <= 0 || line.substring(0, separator).trim() != CONFIG_VERSION_KEY) {
                return@forEach
            }
            require(declaredVersion == null) { "Duplicate Emotify server config key: $CONFIG_VERSION_KEY" }
            declaredVersion = line.substring(separator + 1).trim().toInt()
        }
        return declaredVersion
    }

    private const val CONFIG_VERSION_KEY = "configVersion"
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
        CONFIG_VERSION_KEY,
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
            FabricServerConfigStorage.load(configPath)
        } catch (exception: Exception) {
            throw IllegalStateException("Failed to load Emotify server config from $configPath", exception)
        }
    }

    fun snapshot(): FabricServerConfigSnapshot = checkNotNull(current) {
        "Emotify Fabric server config has not been initialized"
    }
}

internal object FabricServerConfigStorage {
    fun load(path: Path): FabricServerConfigSnapshot {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return FabricServerConfigSnapshot().also { snapshot -> persist(path, snapshot) }
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Emotify server config is not a regular file: $path"
        }
        val source = ServerConfigurationFileIO.readUtf8(path, MAXIMUM_CONFIG_BYTES)
        return when (val decoded = FabricServerConfigCodec.decode(source)) {
            is FabricServerConfigDecodeResult.Ready -> loadReady(path, decoded)
            is FabricServerConfigDecodeResult.Future -> loadFuture(decoded)
        }
    }

    private fun loadReady(
        path: Path,
        decoded: FabricServerConfigDecodeResult.Ready,
    ): FabricServerConfigSnapshot {
        if (decoded.version == ServerConfigurationVersion.Legacy) {
            ServerConfigurationFileIO.createBackupIfAbsent(
                path,
                path.resolveSibling("${path.fileName}.v0.bak"),
                MAXIMUM_CONFIG_BYTES,
            )
            persist(path, decoded.snapshot)
        }
        return decoded.snapshot
    }

    private fun loadFuture(decoded: FabricServerConfigDecodeResult.Future): FabricServerConfigSnapshot {
        EmotifyFabric.LOGGER.error(
            "Emotify Fabric server config schema {} is newer than supported schema {}; Emotify is disabled and the file remains unchanged",
            decoded.version,
            ServerConfigurationSchema.CURRENT_VERSION,
        )
        return FabricServerConfigSnapshot(enabled = false, customEmojisEnabled = false)
    }

    private fun persist(path: Path, snapshot: FabricServerConfigSnapshot) {
        FabricServerConfigPersistence.write(path, FabricServerConfigCodec.encode(snapshot))
    }

    private const val MAXIMUM_CONFIG_BYTES = 16_384
}

internal object FabricServerConfigPersistence {
    fun write(path: Path, content: String) {
        ServerConfigurationFileIO.writeUtf8Atomically(path, content)
    }
}
