package me.whish.emotify.wire.v1

object ProtocolV1Channels {
    const val CLIENT_HELLO = "emotify:client_hello"
    const val SERVER_HELLO = "emotify:server_hello"
    const val SELECT = "emotify:select"
    const val PLAY = "emotify:play"
    const val SELECTION_REJECTED = "emotify:selection_rejected"
}

object ProtocolV1Limits {
    const val CLIENT_HELLO_BODY_BYTES = 12
    const val SERVER_HELLO_BODY_BYTES = 33_296
    const val PORTABLE_SERVER_HELLO_BODY_BYTES = 4_096
    const val SELECT_BODY_BYTES = 65
    const val PLAY_BODY_BYTES = 95
    const val SELECTION_REJECTED_BODY_BYTES = 3
}

enum class WireDecodeViolation {
    BODY_TOO_LARGE,
    TRUNCATED_BODY,
    TRAILING_BYTES,
    MALFORMED_VAR_INT,
    MALFORMED_VAR_LONG,
    INVALID_EMOTION_ID,
    INVALID_CATALOG,
    INVALID_FIELD_VALUE,
}

enum class WireEncodeViolation {
    BODY_TOO_LARGE,
    DESTINATION_EXHAUSTED,
    UNENCODABLE_VALUE,
    ENCODED_SIZE_MISMATCH,
}

class WireDecodeException(
    val violation: WireDecodeViolation,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class WireEncodeException(
    val violation: WireEncodeViolation,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
