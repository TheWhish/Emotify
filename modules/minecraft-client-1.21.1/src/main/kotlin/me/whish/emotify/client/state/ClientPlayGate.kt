package me.whish.emotify.client.state

import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.EmotionPlay

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
        if (activeConnectionId != connectionId) {
            return false
        }
        if (!allowedCatalog.contains(play.emotionId)) {
            return false
        }
        if (play.entityId.value != sourceEntityId || play.sourceUuid != sourceUuid || !sourceVisible) {
            return false
        }
        if (play.sequence.value <= lastAcceptedSequence) {
            return false
        }

        lastAcceptedSequence = play.sequence.value
        return true
    }
}
