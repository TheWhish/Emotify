package me.whish.emotify.client.picker

import me.whish.emotify.client.state.ClientSelectionSendResult

enum class EmotionPickerAccessDecision {
    OPEN,
    SCREEN_OCCUPIED,
    GAME_CONTEXT_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    EMPTY_CATALOG,
}

object EmotionPickerAccessPolicy {
    fun decide(
        screenOpen: Boolean,
        worldAvailable: Boolean,
        connectionAvailable: Boolean,
        playerAvailable: Boolean,
        serverContextAvailable: Boolean,
        catalogAvailable: Boolean,
    ): EmotionPickerAccessDecision = when {
        screenOpen -> EmotionPickerAccessDecision.SCREEN_OCCUPIED
        !worldAvailable || !connectionAvailable || !playerAvailable ->
            EmotionPickerAccessDecision.GAME_CONTEXT_UNAVAILABLE
        !serverContextAvailable -> EmotionPickerAccessDecision.SERVER_UNAVAILABLE
        !catalogAvailable -> EmotionPickerAccessDecision.EMPTY_CATALOG
        else -> EmotionPickerAccessDecision.OPEN
    }
}

class EmotionPickerOpenRequests(
    private val consumeClick: () -> Boolean,
) {
    fun drain(): Boolean {
        var requested = false
        while (consumeClick()) {
            requested = true
        }
        return requested
    }
}

object ClientSelectionEligibility {
    fun canPublish(alive: Boolean, spectator: Boolean, invisible: Boolean): Boolean =
        alive && !spectator && !invisible
}

fun ClientSelectionSendResult.messageTranslationKey(): String? = when (this) {
    ClientSelectionSendResult.SENT -> null
    ClientSelectionSendResult.NOT_CONNECTED,
    ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE,
    ClientSelectionSendResult.CHANNEL_UNAVAILABLE,
    -> "message.emotify.unavailable"
    ClientSelectionSendResult.EMOTION_UNAVAILABLE -> "message.emotify.selection_unavailable"
    ClientSelectionSendResult.PLAYER_STATE -> "message.emotify.player_state"
    ClientSelectionSendResult.REQUEST_PENDING -> "message.emotify.request_pending"
    ClientSelectionSendResult.REQUEST_THROTTLED -> "message.emotify.request_throttled"
    ClientSelectionSendResult.EMOTION_ACTIVE -> "message.emotify.emotion_active"
}
