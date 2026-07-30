package me.whish.emotify.client.state

enum class ClientSelectionSendResult {
    SENT,
    NOT_CONNECTED,
    HANDSHAKE_UNAVAILABLE,
    EMOTION_UNAVAILABLE,
    PLAYER_STATE,
    CHANNEL_UNAVAILABLE,
    REQUEST_PENDING,
    REQUEST_THROTTLED,
    EMOTION_ACTIVE,
}
