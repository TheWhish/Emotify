package me.whish.emotify.client.settings

import java.util.UUID
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.EmotionId

object ClientEmotionVisibility {
    fun allowsBuiltIn(
        localSource: Boolean,
        sourceUuid: UUID,
        sourceName: String,
        settings: ClientSettingsSnapshot,
    ): Boolean = allowsRemoteSource(localSource, sourceUuid, sourceName, settings)

    fun allowsCustom(
        localSource: Boolean,
        sourceUuid: UUID,
        sourceName: String,
        settings: ClientSettingsSnapshot,
    ): Boolean =
        localSource ||
            settings.showCustomEmotions &&
            allowsRemoteSource(false, sourceUuid, sourceName, settings)

    fun allowsActive(
        localSource: Boolean,
        sourceUuid: UUID,
        sourceName: String,
        emotionId: EmotionId,
        settings: ClientSettingsSnapshot,
    ): Boolean = if (CustomEmojiId.parse(emotionId) == null) {
        allowsBuiltIn(localSource, sourceUuid, sourceName, settings)
    } else {
        allowsCustom(localSource, sourceUuid, sourceName, settings)
    }

    private fun allowsRemoteSource(
        localSource: Boolean,
        sourceUuid: UUID,
        sourceName: String,
        settings: ClientSettingsSnapshot,
    ): Boolean =
        localSource || settings.showOtherPlayers && !settings.isPlayerIgnored(sourceUuid, sourceName)
}
