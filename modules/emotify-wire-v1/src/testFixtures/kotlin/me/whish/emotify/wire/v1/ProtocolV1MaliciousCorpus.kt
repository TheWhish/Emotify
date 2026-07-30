package me.whish.emotify.wire.v1

enum class ProtocolV1PayloadKind {
    CLIENT_HELLO,
    SERVER_HELLO,
    SELECTION,
    PLAY,
    SELECTION_REJECTED,
}

class ProtocolV1MaliciousInput(
    val name: String,
    val payloadKind: ProtocolV1PayloadKind,
    val bytes: ByteArray,
    val violation: WireDecodeViolation,
)

object ProtocolV1MaliciousCorpus {
    val inputs: List<ProtocolV1MaliciousInput> = listOf(
        input("empty client hello", ProtocolV1PayloadKind.CLIENT_HELLO, byteArrayOf(), WireDecodeViolation.TRUNCATED_BODY),
        input(
            "oversized client hello",
            ProtocolV1PayloadKind.CLIENT_HELLO,
            ByteArray(ProtocolV1Limits.CLIENT_HELLO_BODY_BYTES + 1),
            WireDecodeViolation.BODY_TOO_LARGE,
        ),
        input(
            "non canonical client flags",
            ProtocolV1PayloadKind.CLIENT_HELLO,
            hex("01 00 80 00"),
            WireDecodeViolation.MALFORMED_VAR_LONG,
        ),
        input(
            "overflowing client flags",
            ProtocolV1PayloadKind.CLIENT_HELLO,
            hex("01 00 80 80 80 80 80 80 80 80 80 02"),
            WireDecodeViolation.MALFORMED_VAR_LONG,
        ),
        input(
            "trailing client hello byte",
            ProtocolV1PayloadKind.CLIENT_HELLO,
            hex("01 00 00 00"),
            WireDecodeViolation.TRAILING_BYTES,
        ),
        input(
            "oversized server hello",
            ProtocolV1PayloadKind.SERVER_HELLO,
            ByteArray(ProtocolV1Limits.SERVER_HELLO_BODY_BYTES + 1),
            WireDecodeViolation.BODY_TOO_LARGE,
        ),
        input(
            "truncated server catalog count",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 FA 01"),
            WireDecodeViolation.TRUNCATED_BODY,
        ),
        input(
            "server cooldown below minimum",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 F9 01 00"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "server cooldown above maximum",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 91 4E 00"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "server catalog above maximum",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 FA 01 81 04"),
            WireDecodeViolation.INVALID_CATALOG,
        ),
        input(
            "non canonical server catalog count",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 FA 01 80 00"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "non ASCII server emotion ID",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 FA 01 01 03 61 3A 80"),
            WireDecodeViolation.INVALID_EMOTION_ID,
        ),
        input(
            "trailing server hello byte",
            ProtocolV1PayloadKind.SERVER_HELLO,
            hex("01 00 00 FA 01 00 00"),
            WireDecodeViolation.TRAILING_BYTES,
        ),
        input("empty selection", ProtocolV1PayloadKind.SELECTION, byteArrayOf(), WireDecodeViolation.TRUNCATED_BODY),
        input(
            "oversized selection",
            ProtocolV1PayloadKind.SELECTION,
            ByteArray(ProtocolV1Limits.SELECT_BODY_BYTES + 1),
            WireDecodeViolation.BODY_TOO_LARGE,
        ),
        input(
            "selection ID below minimum",
            ProtocolV1PayloadKind.SELECTION,
            hex("02 61 3A"),
            WireDecodeViolation.INVALID_EMOTION_ID,
        ),
        input(
            "non canonical selection length",
            ProtocolV1PayloadKind.SELECTION,
            hex("83 00 61 3A 62"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "overflowing selection length",
            ProtocolV1PayloadKind.SELECTION,
            hex("FF FF FF FF 10"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "uppercase selection ID",
            ProtocolV1PayloadKind.SELECTION,
            hex("03 41 3A 62"),
            WireDecodeViolation.INVALID_EMOTION_ID,
        ),
        input(
            "non ASCII selection ID",
            ProtocolV1PayloadKind.SELECTION,
            hex("03 61 3A 80"),
            WireDecodeViolation.INVALID_EMOTION_ID,
        ),
        input(
            "truncated selection ID",
            ProtocolV1PayloadKind.SELECTION,
            hex("05 61 3A 62"),
            WireDecodeViolation.TRUNCATED_BODY,
        ),
        input(
            "trailing selection byte",
            ProtocolV1PayloadKind.SELECTION,
            hex("03 61 3A 62 00"),
            WireDecodeViolation.TRAILING_BYTES,
        ),
        input(
            "zero play entity ID",
            ProtocolV1PayloadKind.PLAY,
            playBytes("00", "01"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "negative play entity ID",
            ProtocolV1PayloadKind.PLAY,
            playBytes("FF FF FF FF 0F", "01"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "non canonical play entity ID",
            ProtocolV1PayloadKind.PLAY,
            playBytes("81 00", "01"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "overflowing play entity ID",
            ProtocolV1PayloadKind.PLAY,
            playBytes("FF FF FF FF 10", "01"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "truncated play UUID",
            ProtocolV1PayloadKind.PLAY,
            hex("01 00 00 00 00 00 00 00"),
            WireDecodeViolation.TRUNCATED_BODY,
        ),
        input(
            "zero play sequence",
            ProtocolV1PayloadKind.PLAY,
            playBytes("01", "00"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "negative play sequence",
            ProtocolV1PayloadKind.PLAY,
            playBytes("01", "FF FF FF FF FF FF FF FF FF 01"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "non canonical play sequence",
            ProtocolV1PayloadKind.PLAY,
            playBytes("01", "81 00"),
            WireDecodeViolation.MALFORMED_VAR_LONG,
        ),
        input(
            "overflowing play sequence",
            ProtocolV1PayloadKind.PLAY,
            playBytes("01", "FF FF FF FF FF FF FF FF FF 02"),
            WireDecodeViolation.MALFORMED_VAR_LONG,
        ),
        input(
            "trailing play byte",
            ProtocolV1PayloadKind.PLAY,
            playBytes("01", "01") + byteArrayOf(0),
            WireDecodeViolation.TRAILING_BYTES,
        ),
        input(
            "oversized play",
            ProtocolV1PayloadKind.PLAY,
            ByteArray(ProtocolV1Limits.PLAY_BODY_BYTES + 1),
            WireDecodeViolation.BODY_TOO_LARGE,
        ),
        input(
            "empty rejection",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            byteArrayOf(),
            WireDecodeViolation.TRUNCATED_BODY,
        ),
        input(
            "truncated rejection retry",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            hex("00 80"),
            WireDecodeViolation.TRUNCATED_BODY,
        ),
        input(
            "rejection retry above maximum",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            hex("00 91 4E"),
            WireDecodeViolation.INVALID_FIELD_VALUE,
        ),
        input(
            "non canonical rejection retry",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            hex("00 80 00"),
            WireDecodeViolation.MALFORMED_VAR_INT,
        ),
        input(
            "trailing rejection byte",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            hex("00 00 00"),
            WireDecodeViolation.TRAILING_BYTES,
        ),
        input(
            "oversized rejection",
            ProtocolV1PayloadKind.SELECTION_REJECTED,
            ByteArray(ProtocolV1Limits.SELECTION_REJECTED_BODY_BYTES + 1),
            WireDecodeViolation.BODY_TOO_LARGE,
        ),
    )

    val duplicateCatalog = hex("01 00 00 FA 01 02 03 61 3A 62 03 61 3A 62")
    val invalidCooldownWithDuplicateCatalog = hex("01 00 00 F9 01 02 03 61 3A 62 03 61 3A 62")
    val duplicateCatalogWithTruncatedTail = hex("01 00 00 FA 01 03 03 61 3A 62 03 61 3A 62 05 61 3A 62")

    private fun input(
        name: String,
        payloadKind: ProtocolV1PayloadKind,
        bytes: ByteArray,
        violation: WireDecodeViolation,
    ): ProtocolV1MaliciousInput = ProtocolV1MaliciousInput(name, payloadKind, bytes, violation)

    private fun playBytes(entityId: String, sequence: String): ByteArray =
        hex(entityId) + ByteArray(16) + hex(sequence) + hex("03 61 3A 62")
}

private fun hex(value: String): ByteArray = value
    .split(' ')
    .filter(String::isNotEmpty)
    .map { encoded -> encoded.toInt(16).toByte() }
    .toByteArray()
