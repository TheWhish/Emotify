package me.whish.emotify.client.settings

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

@ConsistentCopyVisibility
data class IgnoredPlayerIdentity private constructor(
    val uuid: UUID,
    val name: String,
) {
    val normalizedName = normalizeName(name)

    fun matches(candidateUuid: UUID, candidateName: String): Boolean =
        uuid == candidateUuid || normalizedName == normalizeObservedName(candidateName)

    companion object {
        const val MAXIMUM_NAME_LENGTH = 64
        const val MAXIMUM_NAME_UTF8_BYTES = 64

        fun of(uuid: UUID, name: String): IgnoredPlayerIdentity {
            val displayName = name.trim()
            require(displayName.isNotEmpty()) { "Ignored player name must not be empty" }
            require(displayName.length <= MAXIMUM_NAME_LENGTH) {
                "Ignored player name exceeds $MAXIMUM_NAME_LENGTH characters"
            }
            require(displayName.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_NAME_UTF8_BYTES) {
                "Ignored player name exceeds $MAXIMUM_NAME_UTF8_BYTES UTF-8 bytes"
            }
            require(displayName.none(Char::isISOControl)) { "Ignored player name contains control characters" }
            return IgnoredPlayerIdentity(uuid, displayName)
        }

        fun normalizeObservedName(name: String): String? {
            val candidate = name.trim()
            if (
                candidate.isEmpty() ||
                candidate.length > MAXIMUM_NAME_LENGTH ||
                candidate.any(Char::isISOControl)
            ) {
                return null
            }
            return normalizeName(candidate)
        }

        private fun normalizeName(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}

class ClientSettingsSnapshot private constructor(
    val showOtherPlayers: Boolean,
    val showCustomEmotions: Boolean,
    val showHotbarFeedback: Boolean,
    val reducedMotion: Boolean,
    val soundVolumePercent: Int,
    val ignoredPlayers: List<IgnoredPlayerIdentity>,
    private val ignoredUuids: Set<UUID>,
    private val ignoredNames: Set<String>,
) {
    fun isPlayerIgnored(uuid: UUID, name: String): Boolean {
        if (uuid in ignoredUuids) {
            return true
        }
        if (ignoredNames.isEmpty()) {
            return false
        }
        val normalizedName = IgnoredPlayerIdentity.normalizeObservedName(name) ?: return false
        return normalizedName in ignoredNames
    }

    fun withShowOtherPlayers(enabled: Boolean): ClientSettingsSnapshot =
        copy(showOtherPlayers = enabled)

    fun withShowCustomEmotions(enabled: Boolean): ClientSettingsSnapshot =
        copy(showCustomEmotions = enabled)

    fun withShowHotbarFeedback(enabled: Boolean): ClientSettingsSnapshot =
        copy(showHotbarFeedback = enabled)

    fun withReducedMotion(enabled: Boolean): ClientSettingsSnapshot =
        copy(reducedMotion = enabled)

    fun withSoundVolumePercent(volumePercent: Int): ClientSettingsSnapshot =
        copy(soundVolumePercent = validatedSoundVolume(volumePercent))

    fun withPlayerIgnored(uuid: UUID, name: String, ignored: Boolean): ClientSettingsSnapshot {
        val identity = IgnoredPlayerIdentity.of(uuid, name)
        val retained = ignoredPlayers.filterNot { current -> current.matches(identity.uuid, identity.name) }
        if (!ignored) {
            return create(
                showOtherPlayers,
                reducedMotion,
                soundVolumePercent,
                retained,
                showCustomEmotions,
                showHotbarFeedback,
            )
        }
        if (retained.size >= MAXIMUM_IGNORED_PLAYERS) {
            return this
        }
        return create(
            showOtherPlayers,
            reducedMotion,
            soundVolumePercent,
            retained + identity,
            showCustomEmotions,
            showHotbarFeedback,
        )
    }

    private fun copy(
        showOtherPlayers: Boolean = this.showOtherPlayers,
        showCustomEmotions: Boolean = this.showCustomEmotions,
        showHotbarFeedback: Boolean = this.showHotbarFeedback,
        reducedMotion: Boolean = this.reducedMotion,
        soundVolumePercent: Int = this.soundVolumePercent,
    ): ClientSettingsSnapshot = ClientSettingsSnapshot(
        showOtherPlayers,
        showCustomEmotions,
        showHotbarFeedback,
        reducedMotion,
        soundVolumePercent,
        ignoredPlayers,
        ignoredUuids,
        ignoredNames,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ClientSettingsSnapshot &&
            showOtherPlayers == other.showOtherPlayers &&
            showCustomEmotions == other.showCustomEmotions &&
            showHotbarFeedback == other.showHotbarFeedback &&
            reducedMotion == other.reducedMotion &&
            soundVolumePercent == other.soundVolumePercent &&
            ignoredPlayers == other.ignoredPlayers

    override fun hashCode(): Int {
        var result = showOtherPlayers.hashCode()
        result = 31 * result + showCustomEmotions.hashCode()
        result = 31 * result + showHotbarFeedback.hashCode()
        result = 31 * result + reducedMotion.hashCode()
        result = 31 * result + soundVolumePercent
        result = 31 * result + ignoredPlayers.hashCode()
        return result
    }

    override fun toString(): String =
        "ClientSettingsSnapshot(showOtherPlayers=$showOtherPlayers, showCustomEmotions=$showCustomEmotions, " +
            "showHotbarFeedback=$showHotbarFeedback, " +
            "reducedMotion=$reducedMotion, " +
            "soundVolumePercent=$soundVolumePercent, ignoredPlayers=$ignoredPlayers)"

    companion object {
        const val MAXIMUM_IGNORED_PLAYERS = 256
        const val MINIMUM_SOUND_VOLUME_PERCENT = 0
        const val MAXIMUM_SOUND_VOLUME_PERCENT = 100

        fun defaults(): ClientSettingsSnapshot = create(
            showOtherPlayers = true,
            reducedMotion = false,
            soundVolumePercent = MAXIMUM_SOUND_VOLUME_PERCENT,
            ignoredPlayers = emptyList(),
            showCustomEmotions = true,
            showHotbarFeedback = true,
        )

        fun create(
            showOtherPlayers: Boolean,
            reducedMotion: Boolean,
            soundVolumePercent: Int,
            ignoredPlayers: Collection<IgnoredPlayerIdentity>,
            showCustomEmotions: Boolean = true,
            showHotbarFeedback: Boolean = true,
        ): ClientSettingsSnapshot {
            validatedSoundVolume(soundVolumePercent)
            val normalizedPlayers = ArrayList<IgnoredPlayerIdentity>(
                ignoredPlayers.size.coerceAtMost(MAXIMUM_IGNORED_PLAYERS),
            )
            for (identity in ignoredPlayers) {
                normalizedPlayers.removeIf { current ->
                    current.uuid == identity.uuid || current.normalizedName == identity.normalizedName
                }
                if (normalizedPlayers.size >= MAXIMUM_IGNORED_PLAYERS) {
                    continue
                }
                normalizedPlayers.add(identity)
            }
            return ClientSettingsSnapshot(
                showOtherPlayers,
                showCustomEmotions,
                showHotbarFeedback,
                reducedMotion,
                soundVolumePercent,
                java.util.List.copyOf(normalizedPlayers),
                java.util.Set.copyOf(normalizedPlayers.map(IgnoredPlayerIdentity::uuid)),
                java.util.Set.copyOf(normalizedPlayers.map(IgnoredPlayerIdentity::normalizedName)),
            )
        }

        private fun validatedSoundVolume(soundVolumePercent: Int): Int {
            require(soundVolumePercent in MINIMUM_SOUND_VOLUME_PERCENT..MAXIMUM_SOUND_VOLUME_PERCENT) {
                "Sound volume must be between $MINIMUM_SOUND_VOLUME_PERCENT and " +
                    "$MAXIMUM_SOUND_VOLUME_PERCENT: $soundVolumePercent"
            }
            return soundVolumePercent
        }
    }
}