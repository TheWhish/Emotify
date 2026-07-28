package me.whish.emotify.domain

const val MAX_SELECTION_RETRY_AFTER_MILLIS = 10_000

enum class SelectionRejectionReason {
    COOLDOWN,
    SERVER_DISABLED,
    EMOTION_DISABLED,
    PLAYER_STATE,
    SERVER_BUSY,
}
