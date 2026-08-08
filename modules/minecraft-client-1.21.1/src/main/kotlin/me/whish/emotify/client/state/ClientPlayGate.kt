package me.whish.emotify.client.state

import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.CustomEmotionPlay

class ClientPlayGate {
    private var activeConnectionId = 0L
    private var lastAcceptedSequence = 0L

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        activeConnectionId = connectionId
        lastAcceptedSequence = 0L
    }

    fun disconnect(connectionId: Long) {
        if (activeConnectionId != connectionId) {
            return
        }
        activeConnectionId = 0L
        lastAcceptedSequence = 0L
    }

    fun admit(
        connectionId: Long,
        allowedCatalog: EmotionCatalog,
        play: EmotionPlay,
        sourceEntityId: Int,
        sourceUuid: UUID,
        sourceVisible: Boolean,
    ): Boolean {
        if (!allowedCatalog.contains(play.emotionId)) {
            return false
        }
        return admitIdentity(
            connectionId,
            play.entityId.value,
            play.sourceUuid,
            play.sequence.value,
            sourceEntityId,
            sourceUuid,
            sourceVisible,
        )
    }

    fun admitCustom(
        connectionId: Long,
        play: CustomEmotionPlay,
        sourceEntityId: Int,
        sourceUuid: UUID,
        sourceVisible: Boolean,
    ): Boolean = admitIdentity(
        connectionId,
        play.entityId.value,
        play.sourceUuid,
        play.sequence.value,
        sourceEntityId,
        sourceUuid,
        sourceVisible,
    )

    private fun admitIdentity(
        connectionId: Long,
        entityId: Int,
        sourceUuid: UUID,
        sequence: Long,
        expectedEntityId: Int,
        expectedSourceUuid: UUID,
        sourceVisible: Boolean,
    ): Boolean {
        if (activeConnectionId != connectionId) {
            return false
        }
        if (entityId != expectedEntityId || sourceUuid != expectedSourceUuid || !sourceVisible) {
            return false
        }
        if (sequence <= lastAcceptedSequence) {
            return false
        }

        lastAcceptedSequence = sequence
        return true
    }
}
