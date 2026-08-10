package me.whish.emotify.wire.v1

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels
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

internal fun WireWriter.writeCustomEmojiId(id: CustomEmojiId) {
    writeLong(id.mostSignificantBits)
    writeLong(id.middleBits)
    writeLong(id.leastSignificantBits)
}

internal fun WireReader.readCustomEmojiId(): CustomEmojiId = CustomEmojiId(
    readLong(),
    readLong(),
    readLong(),
)

internal fun customEmojiDescriptorSize(descriptor: CustomEmojiDescriptor): Int {
    val nameBytes = descriptor.displayName.toByteArray(StandardCharsets.UTF_8)
    return CustomEmojiId.BYTE_LENGTH + varIntSize(nameBytes.size) + nameBytes.size
}

internal fun WireWriter.writeCustomEmojiDescriptor(descriptor: CustomEmojiDescriptor) {
    val nameBytes = descriptor.displayName.toByteArray(StandardCharsets.UTF_8)
    writeCustomEmojiId(descriptor.originId)
    writeVarInt(nameBytes.size)
    writeBytes(nameBytes)
}

internal fun WireReader.readCustomEmojiDescriptor(): CustomEmojiDescriptor {
    val originId = readCustomEmojiId()
    val nameLength = readCanonicalVarInt()
    if (nameLength !in 1..CustomEmojiDescriptor.MAXIMUM_DISPLAY_NAME_UTF8_BYTES) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_CUSTOM_EMOJI,
            "Custom emoji display name length is outside safe limits",
        )
    }
    if (remainingBytes < nameLength) {
        throw WireDecodeException(WireDecodeViolation.TRUNCATED_BODY, "Custom emoji display name is truncated")
    }
    val nameBytes = readBytes(nameLength)
    val displayName = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(nameBytes))
            .toString()
    } catch (exception: CharacterCodingException) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_CUSTOM_EMOJI,
            "Custom emoji display name is not valid UTF-8",
            exception,
        )
    }
    val descriptor = try {
        CustomEmojiDescriptor.create(displayName, originId)
    } catch (exception: IllegalArgumentException) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_CUSTOM_EMOJI,
            "Custom emoji descriptor is invalid",
            exception,
        )
    }
    if (descriptor.displayName != displayName) {
        throw WireDecodeException(
            WireDecodeViolation.INVALID_CUSTOM_EMOJI,
            "Custom emoji display name is not canonical",
        )
    }
    return descriptor
}

internal fun customEmojiAssetSize(asset: CustomEmojiAsset): Int =
    CustomEmojiAssetEncodingPlan.create(asset).encodedSize

internal fun WireWriter.writeCustomEmojiAsset(asset: CustomEmojiAsset) {
    CustomEmojiAssetEncodingPlan.create(asset).encode(this)
}

internal class CustomEmojiAssetEncodingPlan private constructor(
    private val asset: CustomEmojiAsset,
    private val frames: List<CustomEmojiFrameEncodingPlan>,
    val encodedSize: Int,
) {
    fun encode(writer: WireWriter) {
        if (!asset.isAnimated) {
            writer.writeUnsignedByte(asset.pixels.size)
            frames.single().encode(writer)
            return
        }
        writer.writeUnsignedByte(ANIMATED_PIXELS)
        writer.writeUnsignedByte(asset.pixels.size)
        writer.writeUnsignedByte(asset.frames.size)
        asset.frames.forEachIndexed { index, frame ->
            writer.writeVarInt(frame.durationMillis)
            frames[index].encode(writer)
        }
    }

    companion object {
        fun create(asset: CustomEmojiAsset): CustomEmojiAssetEncodingPlan {
            requireLegacyCustomEmojiSize(asset.pixels.size)
            val frames = asset.frames.map { frame -> CustomEmojiFrameEncodingPlan.create(frame.pixels) }
            val encodedSize = if (!asset.isAnimated) {
                1 + frames.single().encodedSize
            } else {
                3 + asset.frames.indices.sumOf { index ->
                    varIntSize(asset.frames[index].durationMillis) + frames[index].encodedSize
                }
            }
            return CustomEmojiAssetEncodingPlan(asset, java.util.List.copyOf(frames), encodedSize)
        }
    }
}

internal class CustomEmojiFrameEncodingPlan private constructor(
    private val pixels: CustomEmojiPixels,
    private val palette: CustomEmojiPalette?,
    val encodedSize: Int,
) {
    fun encode(writer: WireWriter) {
        val preparedPalette = palette
        if (preparedPalette == null) {
            writer.writeUnsignedByte(RAW_PIXELS)
            repeat(pixels.pixelCount) { index -> writer.writeInt(pixels.colorAt(index)) }
            return
        }
        writer.writeUnsignedByte(PALETTE_PIXELS)
        writer.writeUnsignedByte(preparedPalette.colors.size)
        preparedPalette.colors.forEach(writer::writeInt)
        writer.writeUnsignedByte(preparedPalette.bitsPerIndex)
        var accumulator = 0
        var accumulatedBits = 0
        preparedPalette.indices.forEach { index ->
            accumulator = accumulator or (index shl accumulatedBits)
            accumulatedBits += preparedPalette.bitsPerIndex
            while (accumulatedBits >= Byte.SIZE_BITS) {
                writer.writeUnsignedByte(accumulator and 0xFF)
                accumulator = accumulator ushr Byte.SIZE_BITS
                accumulatedBits -= Byte.SIZE_BITS
            }
        }
        if (accumulatedBits > 0) {
            writer.writeUnsignedByte(accumulator)
        }
    }

    companion object {
        fun create(pixels: CustomEmojiPixels): CustomEmojiFrameEncodingPlan {
            val palette = customEmojiPalette(pixels)
            return CustomEmojiFrameEncodingPlan(
                pixels,
                palette,
                palette?.encodedSize ?: (1 + pixels.rawByteLength),
            )
        }
    }
}

internal fun WireReader.readCustomEmojiAsset(id: CustomEmojiId): CustomEmojiAsset {
    val formatOrSize = readUnsignedByte()
    val asset = if (formatOrSize == ANIMATED_PIXELS) {
        val size = readCustomEmojiSize()
        val frameCount = readUnsignedByte()
        if (frameCount !in 2..CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
            throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Invalid custom emoji frame count")
        }
        var cycleDurationMillis = 0
        val frames = ArrayList<CustomEmojiFrame>(frameCount)
        repeat(frameCount) {
            val durationMillis = readCanonicalVarInt()
            if (durationMillis !in CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS..CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS) {
                throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Invalid custom emoji frame duration")
            }
            cycleDurationMillis += durationMillis
            if (cycleDurationMillis > CustomEmojiAsset.MAXIMUM_CYCLE_DURATION_MILLIS) {
                throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Custom emoji animation cycle is too long")
            }
            frames += CustomEmojiFrame(readCustomEmojiPixels(size), durationMillis)
        }
        CustomEmojiAsset.verify(id, frames)
    } else {
        CustomEmojiAsset.verify(id, readCustomEmojiPixels(formatOrSize))
    }
    return asset ?: throw WireDecodeException(
        WireDecodeViolation.INVALID_CUSTOM_EMOJI,
        "Custom emoji content does not match its ID",
    )
}

private fun WireReader.readCustomEmojiSize(): Int = readUnsignedByte().also { size ->
    if (size != LEGACY_MINIMUM_CUSTOM_EMOJI_SIZE && size != LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Invalid custom emoji dimensions")
    }
}

private fun requireLegacyCustomEmojiSize(size: Int) {
    if (size != LEGACY_MINIMUM_CUSTOM_EMOJI_SIZE && size != LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
        throw WireEncodeException(
            WireEncodeViolation.UNENCODABLE_VALUE,
            "Legacy custom emoji dimensions must be 8x8 or 16x16: ${size}x$size",
        )
    }
}

private fun WireReader.readCustomEmojiPixels(size: Int): CustomEmojiPixels {
    if (size != LEGACY_MINIMUM_CUSTOM_EMOJI_SIZE && size != LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Invalid custom emoji dimensions")
    }
    return when (readUnsignedByte()) {
        RAW_PIXELS -> CustomEmojiPixels.of(size, IntArray(size * size) { readInt() })
        PALETTE_PIXELS -> readPaletteCustomEmojiPixels(size)
        else -> throw WireDecodeException(
            WireDecodeViolation.INVALID_CUSTOM_EMOJI,
            "Unknown custom emoji pixel encoding",
        )
    }
}

private fun WireReader.readPaletteCustomEmojiPixels(size: Int): CustomEmojiPixels {
    val pixelCount = size * size
    val colorCount = readUnsignedByte()
    if (colorCount !in 1..minOf(pixelCount, MAXIMUM_PALETTE_COLORS)) {
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Invalid custom emoji palette size")
    }
    val colors = IntArray(colorCount) { readInt() }
    val bitsPerIndex = readUnsignedByte()
    if (bitsPerIndex != bitsForPalette(colorCount)) {
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Non-canonical custom emoji palette width")
    }
    val pixels = IntArray(pixelCount)
    var accumulator = 0
    var accumulatedBits = 0
    val mask = (1 shl bitsPerIndex) - 1
    for (pixelIndex in pixels.indices) {
        while (accumulatedBits < bitsPerIndex) {
            accumulator = accumulator or (readUnsignedByte() shl accumulatedBits)
            accumulatedBits += Byte.SIZE_BITS
        }
        val paletteIndex = accumulator and mask
        if (paletteIndex >= colorCount) {
            throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Custom emoji palette index is invalid")
        }
        pixels[pixelIndex] = colors[paletteIndex]
        accumulator = accumulator ushr bitsPerIndex
        accumulatedBits -= bitsPerIndex
    }
    if (accumulator != 0) {
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, "Custom emoji padding is not zero")
    }
    return CustomEmojiPixels.of(size, pixels)
}

private fun customEmojiPalette(pixels: CustomEmojiPixels): CustomEmojiPalette? {
    val colors = IntArray(minOf(pixels.pixelCount, MAXIMUM_PALETTE_COLORS))
    val indices = IntArray(pixels.pixelCount)
    val slots = IntArray(paletteSlotCount(pixels.pixelCount))
    val slotMask = slots.lastIndex
    var colorCount = 0
    repeat(pixels.pixelCount) { pixelIndex ->
        val color = pixels.colorAt(pixelIndex)
        var slot = paletteHash(color) and slotMask
        while (true) {
            val encodedIndex = slots[slot]
            if (encodedIndex == EMPTY_PALETTE_SLOT) {
                if (colorCount == MAXIMUM_PALETTE_COLORS) {
                    return null
                }
                colors[colorCount] = color
                slots[slot] = colorCount + 1
                indices[pixelIndex] = colorCount
                colorCount += 1
                break
            }
            val paletteIndex = encodedIndex - 1
            if (colors[paletteIndex] == color) {
                indices[pixelIndex] = paletteIndex
                break
            }
            slot = (slot + 1) and slotMask
        }
    }
    val bitsPerIndex = bitsForPalette(colorCount)
    val encodedSize = 3 + colorCount * Int.SIZE_BYTES +
        (pixels.pixelCount * bitsPerIndex + 7) / 8
    if (encodedSize >= 1 + pixels.rawByteLength) {
        return null
    }
    return CustomEmojiPalette(colors.copyOf(colorCount), indices, bitsPerIndex, encodedSize)
}

private fun paletteSlotCount(pixelCount: Int): Int {
    val requiredSlots = minOf(pixelCount, MAXIMUM_PALETTE_COLORS) * 2
    return Integer.highestOneBit(requiredSlots - 1) shl 1
}

private fun paletteHash(color: Int): Int {
    var hash = color
    hash = (hash xor (hash ushr 16)) * -2_048_144_789
    hash = (hash xor (hash ushr 13)) * -1_028_477_387
    return hash xor (hash ushr 16)
}

private fun bitsForPalette(colorCount: Int): Int = when {
    colorCount <= 2 -> 1
    colorCount <= 4 -> 2
    colorCount <= 8 -> 3
    colorCount <= 16 -> 4
    colorCount <= 32 -> 5
    colorCount <= 64 -> 6
    colorCount <= 128 -> 7
    else -> 8
}

private const val MAXIMUM_PALETTE_COLORS = 255
private const val EMPTY_PALETTE_SLOT = 0

private fun WireWriter.writeInt(value: Int) {
    for (shift in 24 downTo 0 step 8) {
        writeUnsignedByte(value ushr shift and 0xFF)
    }
}

private fun WireReader.readInt(): Int {
    var result = 0
    repeat(Int.SIZE_BYTES) {
        result = result shl Byte.SIZE_BITS or readUnsignedByte()
    }
    return result
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

internal fun WireWriter.writeLong(value: Long) {
    for (shift in 56 downTo 0 step 8) {
        writeUnsignedByte((value ushr shift and 0xFF).toInt())
    }
}

internal fun WireReader.readLong(): Long {
    var result = 0L
    repeat(Long.SIZE_BYTES) {
        result = result shl Byte.SIZE_BITS or readUnsignedByte().toLong()
    }
    return result
}

private class CustomEmojiPalette(
    val colors: IntArray,
    val indices: IntArray,
    val bitsPerIndex: Int,
    val encodedSize: Int,
)

private const val MAX_VAR_INT_BYTES = 5
private const val MAX_VAR_LONG_BYTES = 10
private const val MIN_EMOTION_ID_BYTES = 3
private const val MIN_ENCODED_EMOTION_ID_BYTES = 4
private const val MAX_ASCII = 0x7F
private const val RAW_PIXELS = 0
private const val PALETTE_PIXELS = 1
private const val ANIMATED_PIXELS = 0
private const val LEGACY_MINIMUM_CUSTOM_EMOJI_SIZE = 8
private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
