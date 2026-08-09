package me.whish.emotify.wire.v1

object ProtocolV1Channels {
    const val CLIENT_HELLO = "emotify:client_hello"
    const val SERVER_HELLO = "emotify:server_hello"
    const val SELECT = "emotify:select"
    const val PLAY = "emotify:play"
    const val SELECTION_REJECTED = "emotify:selection_rejected"
    const val CUSTOM_SELECT = "emotify:custom_select"
    const val CUSTOM_ASSET = "emotify:custom_asset"
    const val CUSTOM_ASSET_CHUNK = "emotify:custom_asset_chunk"
    const val CUSTOM_PLAY = "emotify:custom_play"
}

object ProtocolV1Limits {
    const val CLIENT_HELLO_BODY_BYTES = 12
    const val SERVER_HELLO_BODY_BYTES = 33_296
    const val PORTABLE_SERVER_HELLO_BODY_BYTES = 4_096
    const val SELECT_BODY_BYTES = 65
    const val PLAY_BODY_BYTES = 95
    const val SELECTION_REJECTED_BODY_BYTES = 3
    const val CUSTOM_SELECT_BODY_BYTES = 30_978
    const val CUSTOM_ASSET_BODY_BYTES = 30_823
    const val CUSTOM_ASSET_CHUNK_BODY_BYTES = 30_810
    const val CUSTOM_PLAY_BODY_BYTES = 209
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
    INVALID_CUSTOM_EMOJI,
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
