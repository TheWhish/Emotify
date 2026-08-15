package me.whish.emotify.fabric.config

import me.whish.emotify.client.settings.ClientConfigurationMigration
import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.client.settings.ClientConfigurationVersion
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

sealed interface FabricClientConfigDecodeResult {
    data class Ready(
        val snapshot: ClientConfigurationSnapshot,
        val migrationRequired: Boolean,
    ) : FabricClientConfigDecodeResult

    data class Future(val schemaVersion: Int) : FabricClientConfigDecodeResult
}

object FabricClientConfigCodec {
    fun decode(source: String, defaults: ClientConfigurationSnapshot): FabricClientConfigDecodeResult {
        val entries = parseEntries(source)
        return when (val version = ClientConfigurationSchema.classify(decodeVersion(entries[CONFIG_VERSION_KEY]))) {
            ClientConfigurationVersion.Legacy -> FabricClientConfigDecodeResult.Ready(
                ClientConfigurationMigration.fromLegacy(
                    decodeSettings(entries, defaults.settings, LEGACY_KEYS),
                    decodeFavorites(entries[FAVORITES_KEY], defaults.favorites),
                ),
                migrationRequired = true,
            )
            ClientConfigurationVersion.SchemaOne -> FabricClientConfigDecodeResult.Ready(
                ClientConfigurationMigration.fromSchemaOne(
                    decodeSettings(entries, defaults.settings, PREVIOUS_KEYS),
                    decodeFavorites(entries[FAVORITES_KEY], defaults.favorites),
                    decodeQuickSlots(entries[QUICK_SLOTS_KEY], defaults.quickSlots),
                ),
                migrationRequired = true,
            )
            ClientConfigurationVersion.Current -> FabricClientConfigDecodeResult.Ready(
                ClientConfigurationSnapshot.create(
                    decodeSettings(entries, defaults.settings, CURRENT_KEYS),
                    decodeFavorites(entries[FAVORITES_KEY], defaults.favorites),
                    decodeQuickSlots(entries[QUICK_SLOTS_KEY], defaults.quickSlots),
                    decodeBoolean(
                        entries[CUSTOM_COPY_HINT_DISMISSED_KEY],
                        defaults.customCopyHintDismissed,
                    ),
                ),
                migrationRequired = false,
            )
            is ClientConfigurationVersion.Future -> FabricClientConfigDecodeResult.Future(version.value)
        }
    }

    fun encode(snapshot: ClientConfigurationSnapshot): String = buildString {
        append(CONFIG_VERSION_KEY)
        append('=')
        append(snapshot.schemaVersion)
        append('\n')
        append("showOtherPlayersEmotions=")
        append(snapshot.settings.showOtherPlayers)
        append('\n')
        append("showCustomEmotions=")
        append(snapshot.settings.showCustomEmotions)
        append('\n')
        append("reducedMotion=")
        append(snapshot.settings.reducedMotion)
        append('\n')
        append("soundVolumePercent=")
        append(snapshot.settings.soundVolumePercent)
        append('\n')
        append("ignoredPlayers=")
        append(snapshot.settings.ignoredPlayers.joinToString(",", transform = IgnoredPlayerIdentityCodec::encode))
        append('\n')
        append(FAVORITES_KEY)
        append('=')
        append(snapshot.favorites.joinToString(",", transform = EmotionId::value))
        append('\n')
        append(QUICK_SLOTS_KEY)
        append('=')
        append(snapshot.quickSlots.joinToString(",") { emotionId -> emotionId?.value.orEmpty() })
        append('\n')
        append(CUSTOM_COPY_HINT_DISMISSED_KEY)
        append('=')
        append(snapshot.customCopyHintDismissed)
        append('\n')
    }

    private fun parseEntries(source: String): Map<String, String> {
        val entries = LinkedHashMap<String, String>(CURRENT_KEYS.size)
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid Emotify client config line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(entries.putIfAbsent(key, value) == null) { "Duplicate Emotify client config key: $key" }
        }
        return entries
    }

    private fun decodeVersion(value: String?): Int? {
        if (value == null) {
            return null
        }
        return requireNotNull(value.toIntOrNull()) { "Invalid Emotify client config version: $value" }
    }

    private fun decodeSettings(
        entries: Map<String, String>,
        defaults: ClientSettingsSnapshot,
        allowedKeys: Set<String>,
    ): ClientSettingsSnapshot {
        val unknownKeys = entries.keys - allowedKeys
        require(unknownKeys.isEmpty()) { "Unknown Emotify client config key: ${unknownKeys.first()}" }
        return ClientSettingsSnapshot.create(
            entries[SHOW_OTHER_PLAYERS_KEY]?.toBooleanStrict() ?: defaults.showOtherPlayers,
            entries[REDUCED_MOTION_KEY]?.toBooleanStrict() ?: defaults.reducedMotion,
            entries[SOUND_VOLUME_KEY]?.let(::decodeSoundVolume) ?: defaults.soundVolumePercent,
            entries[IGNORED_PLAYERS_KEY]?.let(::decodeIgnoredPlayers) ?: defaults.ignoredPlayers,
            entries[SHOW_CUSTOM_EMOTIONS_KEY]?.toBooleanStrict() ?: defaults.showCustomEmotions,
        )
    }

    private fun decodeSoundVolume(value: String): Int {
        val volume = requireNotNull(value.toIntOrNull()) { "Invalid Emotify sound volume: $value" }
        require(
            volume in ClientSettingsSnapshot.MINIMUM_SOUND_VOLUME_PERCENT..
                ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
        ) { "Emotify sound volume is outside the supported range: $volume" }
        return volume
    }

    private fun decodeBoolean(value: String?, fallback: Boolean): Boolean =
        value?.toBooleanStrict() ?: fallback

    private fun decodeIgnoredPlayers(value: String): List<IgnoredPlayerIdentity> {
        if (value.isEmpty()) {
            return emptyList()
        }
        val entries = value.split(',')
        require(entries.size <= ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS) {
            "Too many ignored players: ${entries.size}"
        }
        val uuids = HashSet<java.util.UUID>(entries.size)
        val names = HashSet<String>(entries.size)
        return java.util.List.copyOf(
            entries.map { encoded ->
                val identity = IgnoredPlayerIdentityCodec.decode(encoded.trim())
                require(uuids.add(identity.uuid)) { "Duplicate ignored player UUID: ${identity.uuid}" }
                require(names.add(identity.normalizedName)) { "Duplicate ignored player name: ${identity.name}" }
                identity
            },
        )
    }

    private fun decodeFavorites(value: String?, defaults: List<EmotionId>): List<EmotionId> {
        if (value == null) {
            return defaults
        }
        if (value.isEmpty()) {
            return emptyList()
        }
        return value.splitToSequence(',')
            .map(String::trim)
            .onEach { entry -> require(entry.isNotEmpty()) { "Empty favorite emotion ID" } }
            .map { entry -> requireNotNull(EmotionId.parse(entry)) { "Invalid favorite emotion ID: $entry" } }
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList()
    }

    private fun decodeQuickSlots(value: String?, defaults: List<EmotionId?>): List<EmotionId?> {
        if (value == null) {
            return defaults
        }
        val entries = value.split(',', limit = ClientConfigurationSchema.QUICK_SLOT_COUNT + 1)
        require(entries.size == ClientConfigurationSchema.QUICK_SLOT_COUNT) {
            "Emotify client config must contain exactly ${ClientConfigurationSchema.QUICK_SLOT_COUNT} quick slots"
        }
        return entries.map { entry ->
            val candidate = entry.trim()
            if (candidate.isEmpty()) {
                null
            } else {
                requireNotNull(EmotionId.parse(candidate)) { "Invalid quick slot emotion ID: $candidate" }
            }
        }
    }

    private const val CONFIG_VERSION_KEY = "configVersion"
    private const val SHOW_OTHER_PLAYERS_KEY = "showOtherPlayersEmotions"
    private const val SHOW_CUSTOM_EMOTIONS_KEY = "showCustomEmotions"
    private const val REDUCED_MOTION_KEY = "reducedMotion"
    private const val SOUND_VOLUME_KEY = "soundVolumePercent"
    private const val IGNORED_PLAYERS_KEY = "ignoredPlayers"
    private const val FAVORITES_KEY = "favorites"
    private const val QUICK_SLOTS_KEY = "quickSlots"
    private const val CUSTOM_COPY_HINT_DISMISSED_KEY = "customCopyHintDismissed"
    private val LEGACY_KEYS = setOf(
        CONFIG_VERSION_KEY,
        SHOW_OTHER_PLAYERS_KEY,
        SHOW_CUSTOM_EMOTIONS_KEY,
        REDUCED_MOTION_KEY,
        SOUND_VOLUME_KEY,
        IGNORED_PLAYERS_KEY,
        FAVORITES_KEY,
    )
    private val PREVIOUS_KEYS = LEGACY_KEYS + QUICK_SLOTS_KEY
    private val CURRENT_KEYS = PREVIOUS_KEYS + CUSTOM_COPY_HINT_DISMISSED_KEY
}

