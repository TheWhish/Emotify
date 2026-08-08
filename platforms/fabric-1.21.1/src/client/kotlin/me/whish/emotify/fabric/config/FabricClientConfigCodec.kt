package me.whish.emotify.fabric.config

import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

data class FabricClientConfigSnapshot(
    val settings: ClientSettingsSnapshot,
    val favorites: List<EmotionId>,
)

object FabricClientConfigCodec {
    fun decode(source: String, defaults: FabricClientConfigSnapshot): FabricClientConfigSnapshot {
        var showOtherPlayers = defaults.settings.showOtherPlayers
        var showCustomEmotions = defaults.settings.showCustomEmotions
        var reducedMotion = defaults.settings.reducedMotion
        var soundVolumePercent = defaults.settings.soundVolumePercent
        var ignoredPlayers = defaults.settings.ignoredPlayers
        var favorites = defaults.favorites
        val observedKeys = HashSet<String>(6)
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid Emotify client config line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(observedKeys.add(key)) { "Duplicate Emotify client config key: $key" }
            when (key) {
                "showOtherPlayersEmotions" -> showOtherPlayers = value.toBooleanStrict()
                "showCustomEmotions" -> showCustomEmotions = value.toBooleanStrict()
                "reducedMotion" -> reducedMotion = value.toBooleanStrict()
                "soundVolumePercent" -> soundVolumePercent = decodeSoundVolume(value)
                "ignoredPlayers" -> ignoredPlayers = decodeIgnoredPlayers(value)
                "favorites" -> favorites = decodeFavorites(value)
                else -> throw IllegalArgumentException("Unknown Emotify client config key: $key")
            }
        }
        return FabricClientConfigSnapshot(
            ClientSettingsSnapshot.create(
                showOtherPlayers,
                reducedMotion,
                soundVolumePercent,
                ignoredPlayers,
                showCustomEmotions,
            ),
            java.util.List.copyOf(favorites),
        )
    }

    fun encode(snapshot: FabricClientConfigSnapshot): String = buildString {
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
        append("favorites=")
        append(snapshot.favorites.joinToString(",", transform = EmotionId::value))
        append('\n')
    }

    private fun decodeSoundVolume(value: String): Int {
        val volume = requireNotNull(value.toIntOrNull()) { "Invalid Emotify sound volume: $value" }
        require(
            volume in ClientSettingsSnapshot.MINIMUM_SOUND_VOLUME_PERCENT..
                ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
        ) { "Emotify sound volume is outside the supported range: $volume" }
        return volume
    }

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

    private fun decodeFavorites(value: String): List<EmotionId> {
        if (value.isEmpty()) {
            return emptyList()
        }
        return java.util.List.copyOf(
            value.splitToSequence(',')
                .map(String::trim)
                .onEach { entry -> require(entry.isNotEmpty()) { "Empty favorite emotion ID" } }
                .map { entry -> requireNotNull(EmotionId.parse(entry)) { "Invalid favorite emotion ID: $entry" } }
                .distinct()
                .take(EmotionCatalog.MAX_SIZE)
                .toList(),
        )
    }
}
