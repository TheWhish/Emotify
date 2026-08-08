package me.whish.emotify.client.state

import java.util.UUID
import me.whish.emotify.client.settings.ClientEmotionVisibility
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.CustomEmotionPlay

enum class ClientEmotionPlayDisposition {
    REJECTED,
    HIDDEN,
    VISIBLE,
}

class ClientEmotionPlayCoordinator(
    private val gate: ClientPlayGate = ClientPlayGate(),
) {
    fun begin(connectionId: Long) {
        gate.begin(connectionId)
    }

    fun disconnect(connectionId: Long) {
        gate.disconnect(connectionId)
    }

    fun evaluate(
        connectionId: Long,
        allowedCatalog: EmotionCatalog,
        play: EmotionPlay,
        sourceEntityId: Int,
        sourceUuid: UUID,
        sourceVisible: Boolean,
        localSource: Boolean,
        sourceName: String,
        settings: ClientSettingsSnapshot,
    ): ClientEmotionPlayDisposition {
        if (!gate.admit(connectionId, allowedCatalog, play, sourceEntityId, sourceUuid, sourceVisible)) {
            return ClientEmotionPlayDisposition.REJECTED
        }
        return if (ClientEmotionVisibility.allowsBuiltIn(localSource, sourceUuid, sourceName, settings)) {
            ClientEmotionPlayDisposition.VISIBLE
        } else {
            ClientEmotionPlayDisposition.HIDDEN
        }
    }

    fun evaluateCustom(
        connectionId: Long,
        play: CustomEmotionPlay,
        sourceEntityId: Int,
        sourceUuid: UUID,
        sourceVisible: Boolean,
        localSource: Boolean,
        sourceName: String,
        settings: ClientSettingsSnapshot,
    ): ClientEmotionPlayDisposition {
        if (!gate.admitCustom(connectionId, play, sourceEntityId, sourceUuid, sourceVisible)) {
            return ClientEmotionPlayDisposition.REJECTED
        }
        return if (ClientEmotionVisibility.allowsCustom(localSource, sourceUuid, sourceName, settings)) {
            ClientEmotionPlayDisposition.VISIBLE
        } else {
            ClientEmotionPlayDisposition.HIDDEN
        }
    }
}
