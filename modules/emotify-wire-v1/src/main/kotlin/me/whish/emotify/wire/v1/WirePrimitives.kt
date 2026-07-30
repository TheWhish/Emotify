package me.whish.emotify.wire.v1

import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion

internal fun varIntSize(value: Int): Int {
    var remaining = value
    var size = 1
    while (remaining and -128 != 0) {
        remaining = remaining ushr 7
        size++
    }
    return size
}

internal fun varLongSize(value: Long): Int {
    var remaining = value
    var size = 1
    while (remaining and -128L != 0L) {
        remaining = remaining ushr 7
        size++
    }
    return size
}

internal fun emotionIdSize(emotionId: EmotionId): Int =
    varIntSize(emotionId.value.length) + emotionId.value.length

internal fun capabilitiesSize(capabilities: ProtocolCapabilities): Int =
    2 + varLongSize(capabilities.features.bits)

internal fun WireWriter.writeVarInt(value: Int) {
    var remaining = value
    while (remaining and -128 != 0) {
        writeUnsignedByte(remaining and 0x7F or 0x80)
        remaining = remaining ushr 7
    }
    writeUnsignedByte(remaining)
}

internal fun WireWriter.writeVarLong(value: Long) {
    var remaining = value
    while (remaining and -128L != 0L) {
        writeUnsignedByte((remaining and 0x7F or 0x80).toInt())
        remaining = remaining ushr 7
    }
    writeUnsignedByte(remaining.toInt())
}

internal fun WireReader.readCanonicalVarInt(): Int {
    var result = 0
    for (index in 0 until MAX_VAR_INT_BYTES) {
        val current = readUnsignedByte()
        if (index == MAX_VAR_INT_BYTES - 1 && current and 0xF0 != 0) {
            throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_INT, "VarInt exceeds 32 bits")
        }
        result = result or ((current and 0x7F) shl (index * 7))
        if (current and 0x80 == 0) {
            if (index + 1 != varIntSize(result)) {
                throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_INT, "VarInt is not canonical")
            }
            return result
        }
    }
    throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_INT, "VarInt is too long")
}

internal fun WireReader.readCanonicalVarLong(): Long {
    var result = 0L
    for (index in 0 until MAX_VAR_LONG_BYTES) {
        val current = readUnsignedByte()
        if (index == MAX_VAR_LONG_BYTES - 1 && current and 0xFE != 0) {
            throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_LONG, "VarLong exceeds 64 bits")
        }
        result = result or ((current and 0x7F).toLong() shl (index * 7))
        if (current and 0x80 == 0) {
            if (index + 1 != varLongSize(result)) {
                throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_LONG, "VarLong is not canonical")
            }
            return result
        }
    }
    throw WireDecodeException(WireDecodeViolation.MALFORMED_VAR_LONG, "VarLong is too long")
}

internal fun WireWriter.writeCapabilities(capabilities: ProtocolCapabilities) {
    writeUnsignedByte(capabilities.version.major)
    writeUnsignedByte(capabilities.version.minor)
    writeVarLong(capabilities.features.bits)
}

internal fun WireReader.readCapabilities(): ProtocolCapabilities = ProtocolCapabilities(
    ProtocolVersion(readUnsignedByte(), readUnsignedByte()),
    FeatureFlags(readCanonicalVarLong()),
)

internal fun WireWriter.writeEmotionId(emotionId: EmotionId) {
    writeVarInt(emotionId.value.length)
    emotionId.value.forEach { character -> writeUnsignedByte(character.code) }
}

internal fun WireReader.readEmotionId(): EmotionId {
    val length = readCanonicalVarInt()
    if (length !in MIN_EMOTION_ID_BYTES..EmotionId.MAX_ENCODED_LENGTH) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_EMOTION_ID,
            "Emotion ID length is outside Protocol 1 limits: $length",
        )
    }
    if (remainingBytes < length) {
        throw WireDecodeException(WireDecodeViolation.TRUNCATED_BODY, "Emotion ID is truncated")
    }
    val encoded = ByteArray(length)
    for (index in encoded.indices) {
        val current = readUnsignedByte()
        if (current > MAX_ASCII) {
            throw WireDecodeException(WireDecodeViolation.INVALID_EMOTION_ID, "Emotion ID is not ASCII")
        }
        encoded[index] = current.toByte()
    }
    return EmotionId.parse(String(encoded, Charsets.US_ASCII))
        ?: throw WireDecodeException(WireDecodeViolation.INVALID_EMOTION_ID, "Emotion ID is invalid")
}

internal fun WireWriter.writeUuid(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

internal fun WireReader.readUuid(): UUID = UUID(readLong(), readLong())

internal data class DecodedCatalog(
    val ids: List<EmotionId>,
    val containsDuplicates: Boolean,
)

internal fun WireReader.readCatalog(): DecodedCatalog {
    val count = readCanonicalVarInt()
    if (count !in 0..EmotionCatalog.MAX_SIZE) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_CATALOG,
            "Emotion catalog size is outside Protocol 1 limits: $count",
        )
    }
    if (remainingBytes < count * MIN_ENCODED_EMOTION_ID_BYTES) {
        throw WireDecodeException(WireDecodeViolation.TRUNCATED_BODY, "Emotion catalog is truncated")
    }
    val ids = ArrayList<EmotionId>(count)
    val uniqueIds = HashSet<EmotionId>(count * 4 / 3 + 1)
    var containsDuplicates = false
    repeat(count) {
        val emotionId = readEmotionId()
        ids += emotionId
        if (!uniqueIds.add(emotionId)) {
            containsDuplicates = true
        }
    }
    return DecodedCatalog(ids, containsDuplicates)
}

private fun WireWriter.writeLong(value: Long) {
    for (shift in 56 downTo 0 step 8) {
        writeUnsignedByte((value ushr shift and 0xFF).toInt())
    }
}

private fun WireReader.readLong(): Long {
    var result = 0L
    repeat(Long.SIZE_BYTES) {
        result = result shl Byte.SIZE_BITS or readUnsignedByte().toLong()
    }
    return result
}

private const val MAX_VAR_INT_BYTES = 5
private const val MAX_VAR_LONG_BYTES = 10
private const val MIN_EMOTION_ID_BYTES = 3
private const val MIN_ENCODED_EMOTION_ID_BYTES = 4
private const val MAX_ASCII = 0x7F
