package me.whish.emotify.wire.v1

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.protocol.CustomEmojiAssetChunk

enum class CustomEmojiLosslessEncoding {
    RAW,
    DEFLATE,
}

data class CustomEmojiLosslessPreflight(
    val size: Int,
    val frameCount: Int,
    val rawBytes: Int,
    val frameBytes: Int,
    val encodedBytes: Int,
    val encoding: CustomEmojiLosslessEncoding,
) {
    init {
        require(CustomEmojiPixels.supports(size)) { "Unsupported custom emoji preflight dimensions: $size" }
        require(frameCount in 1..CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
            "Invalid custom emoji preflight frame count: $frameCount"
        }
        require(frameCount == 1 || size <= CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE) {
            "Animated custom emoji preflight dimensions exceed the safe limit: $size"
        }
        require(frameBytes == size * size * Int.SIZE_BYTES) {
            "Custom emoji preflight frame size does not match its dimensions: $frameBytes"
        }
        require(rawBytes == frameBytes * frameCount) {
            "Custom emoji preflight decoded size does not match its frames: $rawBytes"
        }
        require(encodedBytes in 1..CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES) {
            "Custom emoji preflight encoded size is outside safe limits: $encodedBytes"
        }
    }
}

object CustomEmojiLosslessCodec {
    const val MAXIMUM_ENCODED_BYTES = 512 * 1_024

    fun encodedSize(asset: CustomEmojiAsset): Int = encode(asset).size

    fun encode(asset: CustomEmojiAsset): ByteArray {
        val transformed = transform(asset)
        val compressed = deflate(transformed)
        val encoding = if (compressed.size < transformed.size) DEFLATE else RAW
        val payload = if (encoding == DEFLATE) compressed else transformed
        val headerSize = 3 + asset.frames.sumOf { frame -> varIntSize(frame.durationMillis) } +
            varIntSize(transformed.size) + 1 + varIntSize(payload.size)
        val writer = ByteArrayWireWriter(headerSize + payload.size)
        writer.writeUnsignedByte(FORMAT_VERSION)
        writer.writeUnsignedByte(asset.pixels.size)
        writer.writeUnsignedByte(asset.frames.size)
        asset.frames.forEach { frame -> writer.writeVarInt(frame.durationMillis) }
        writer.writeVarInt(transformed.size)
        writer.writeUnsignedByte(encoding)
        writer.writeVarInt(payload.size)
        writer.writeBytes(payload)
        return writer.toByteArray().also { encoded ->
            require(encoded.size <= MAXIMUM_ENCODED_BYTES) {
                "Encoded custom emoji exceeds $MAXIMUM_ENCODED_BYTES bytes: ${encoded.size}"
            }
        }
    }

    fun decode(id: CustomEmojiId, encoded: ByteArray): CustomEmojiAsset {
        if (encoded.isEmpty() || encoded.size > MAXIMUM_ENCODED_BYTES) {
            invalid("Encoded custom emoji size is outside safe limits")
        }
        val reader = ByteArrayWireReader(encoded)
        try {
            val header = readHeader(reader, encoded.size)
            val payload = reader.readBytes(header.payloadBytes)
            val transformed = when (header.preflight.encoding) {
                CustomEmojiLosslessEncoding.RAW -> payload
                CustomEmojiLosslessEncoding.DEFLATE -> inflate(payload, header.preflight.rawBytes)
            }
            val frames = restore(header.preflight.size, header.durations, transformed)
            return CustomEmojiAsset.verify(id, frames)
                ?: invalid("Custom emoji content does not match its ID")
        } catch (exception: WireDecodeException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw WireDecodeException(
                WireDecodeViolation.INVALID_CUSTOM_EMOJI,
                "Invalid lossless custom emoji payload",
                exception,
            )
        }
    }

    fun preflightFirstChunk(chunk: CustomEmojiAssetChunk): CustomEmojiLosslessPreflight {
        customEmojiAssetChunkValidationError(chunk)?.let(::invalid)
        if (chunk.index != 0) {
            invalid("Custom emoji lossless preflight requires the first chunk")
        }
        return try {
            readHeader(ByteArrayWireReader(chunk.borrowedData()), chunk.totalBytes).preflight
        } catch (exception: WireDecodeException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw WireDecodeException(
                WireDecodeViolation.INVALID_CUSTOM_EMOJI,
                "Invalid lossless custom emoji header",
                exception,
            )
        }
    }

    private fun readHeader(reader: ByteArrayWireReader, encodedBytes: Int): LosslessHeader {
        if (reader.readUnsignedByte() != FORMAT_VERSION) {
            invalid("Unsupported custom emoji lossless format")
        }
        val size = reader.readUnsignedByte()
        if (!CustomEmojiPixels.supports(size)) {
            invalid("Unsupported custom emoji dimensions")
        }
        val frameCount = reader.readUnsignedByte()
        if (frameCount !in 1..CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
            invalid("Invalid custom emoji frame count")
        }
        if (frameCount > 1 && size > CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE) {
            invalid("Animated custom emoji dimensions exceed the safe limit")
        }
        val durations = IntArray(frameCount) { reader.readCanonicalVarInt() }
        validateDurations(durations)
        val expectedRawBytes = size * size * Int.SIZE_BYTES * frameCount
        val rawBytes = reader.readCanonicalVarInt()
        if (rawBytes != expectedRawBytes || rawBytes > CustomEmojiAsset.MAXIMUM_RAW_BYTE_LENGTH) {
            invalid("Invalid custom emoji decoded size")
        }
        val encoding = when (reader.readUnsignedByte()) {
            RAW -> CustomEmojiLosslessEncoding.RAW
            DEFLATE -> CustomEmojiLosslessEncoding.DEFLATE
            else -> invalid("Unknown custom emoji lossless encoding")
        }
        val payloadBytes = reader.readCanonicalVarInt()
        if (payloadBytes !in 1..MAXIMUM_ENCODED_BYTES) {
            invalid("Invalid custom emoji encoded payload size")
        }
        if (reader.position + payloadBytes != encodedBytes) {
            invalid("Custom emoji encoded payload does not match its declared transfer size")
        }
        if (encoding == CustomEmojiLosslessEncoding.RAW && payloadBytes != rawBytes) {
            invalid("Raw custom emoji payload has an invalid size")
        }
        return LosslessHeader(
            CustomEmojiLosslessPreflight(
                size,
                frameCount,
                rawBytes,
                size * size * Int.SIZE_BYTES,
                encodedBytes,
                encoding,
            ),
            durations,
            payloadBytes,
        )
    }

    private fun transform(asset: CustomEmojiAsset): ByteArray {
        val writer = ByteArrayWireWriter(asset.rawByteLength)
        var previousFrame: IntArray? = null
        asset.frames.forEachIndexed { frameIndex, frame ->
            val colors = frame.pixels.copyColors()
            if (frameIndex == 0) {
                repeat(frame.pixels.height) { y ->
                    var left = 0
                    repeat(frame.pixels.width) { x ->
                        val index = y * frame.pixels.width + x
                        val color = colors[index]
                        writer.writeLosslessInt(color xor left)
                        left = color
                    }
                }
            } else {
                val previous = checkNotNull(previousFrame)
                colors.indices.forEach { index -> writer.writeLosslessInt(colors[index] xor previous[index]) }
            }
            previousFrame = colors
        }
        return writer.toByteArray()
    }

    private fun restore(size: Int, durations: IntArray, transformed: ByteArray): List<CustomEmojiFrame> {
        val reader = ByteArrayWireReader(transformed)
        val frames = ArrayList<CustomEmojiFrame>(durations.size)
        var previousFrame: IntArray? = null
        durations.indices.forEach { frameIndex ->
            val colors = IntArray(size * size)
            if (frameIndex == 0) {
                repeat(size) { y ->
                    var left = 0
                    repeat(size) { x ->
                        val index = y * size + x
                        val color = reader.readLosslessInt() xor left
                        colors[index] = color
                        left = color
                    }
                }
            } else {
                val previous = checkNotNull(previousFrame)
                colors.indices.forEach { index -> colors[index] = reader.readLosslessInt() xor previous[index] }
            }
            frames += CustomEmojiFrame(CustomEmojiPixels.of(size, colors), durations[frameIndex])
            previousFrame = colors
        }
        if (reader.remainingBytes != 0) {
            invalid("Custom emoji transformed payload contains trailing bytes")
        }
        return frames
    }

    private fun validateDurations(durations: IntArray) {
        if (durations.size == 1) {
            if (durations[0] != 0) {
                invalid("Static custom emoji duration must be zero")
            }
            return
        }
        var cycleDuration = 0
        durations.forEach { duration ->
            if (duration !in CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS..CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS) {
                invalid("Custom emoji frame duration is outside safe limits")
            }
            cycleDuration += duration
            if (cycleDuration > CustomEmojiAsset.MAXIMUM_CYCLE_DURATION_MILLIS) {
                invalid("Custom emoji animation cycle is too long")
            }
        }
    }

    private fun deflate(source: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        return try {
            deflater.setInput(source)
            deflater.finish()
            val output = ByteArrayOutputStream(source.size.coerceAtMost(64 * 1_024))
            val buffer = ByteArray(8 * 1_024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                check(written > 0) { "Deflater stopped before completing custom emoji encoding" }
                output.write(buffer, 0, written)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(source: ByteArray, expectedBytes: Int): ByteArray {
        val inflater = Inflater(true)
        val output = ByteArray(expectedBytes)
        return try {
            inflater.setInput(source)
            var position = 0
            while (!inflater.finished() && position < output.size) {
                val written = inflater.inflate(output, position, output.size - position)
                if (written == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        invalid("Compressed custom emoji payload is incomplete")
                    }
                    invalid("Compressed custom emoji payload cannot make progress")
                }
                position += written
            }
            if (!inflater.finished() || position != output.size || inflater.remaining != 0) {
                invalid("Compressed custom emoji payload does not match its declared size")
            }
            output
        } catch (exception: DataFormatException) {
            throw WireDecodeException(
                WireDecodeViolation.INVALID_CUSTOM_EMOJI,
                "Compressed custom emoji payload is malformed",
                exception,
            )
        } finally {
            inflater.end()
        }
    }

    private fun WireWriter.writeLosslessInt(value: Int) {
        for (shift in 24 downTo 0 step 8) {
            writeUnsignedByte(value ushr shift and 0xFF)
        }
    }

    private fun WireReader.readLosslessInt(): Int {
        var result = 0
        repeat(Int.SIZE_BYTES) {
            result = result shl Byte.SIZE_BITS or readUnsignedByte()
        }
        return result
    }

    private fun invalid(message: String): Nothing =
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, message)

    private class LosslessHeader(
        val preflight: CustomEmojiLosslessPreflight,
        val durations: IntArray,
        val payloadBytes: Int,
    )

    private const val FORMAT_VERSION = 1
    private const val RAW = 0
    private const val DEFLATE = 1
}

object CustomEmojiAssetChunker {
    const val MAXIMUM_CHUNK_DATA_BYTES = 30 * 1_024
    const val MAXIMUM_CHUNK_COUNT =
        (CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES + MAXIMUM_CHUNK_DATA_BYTES - 1) /
            MAXIMUM_CHUNK_DATA_BYTES
    const val MAXIMUM_CHUNK_HEADER_BYTES = 29
    const val MAXIMUM_WIRE_BYTES =
        CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES + MAXIMUM_CHUNK_COUNT * MAXIMUM_CHUNK_HEADER_BYTES

    fun split(asset: CustomEmojiAsset): List<CustomEmojiAssetChunk> {
        val encoded = CustomEmojiLosslessCodec.encode(asset)
        val count = (encoded.size + MAXIMUM_CHUNK_DATA_BYTES - 1) / MAXIMUM_CHUNK_DATA_BYTES
        check(count <= MAXIMUM_CHUNK_COUNT) { "Custom emoji transfer exceeds the maximum chunk count: $count" }
        return List(count) { index ->
            val start = index * MAXIMUM_CHUNK_DATA_BYTES
            val end = minOf(start + MAXIMUM_CHUNK_DATA_BYTES, encoded.size)
            CustomEmojiAssetChunk.takeOwnership(
                asset.id,
                encoded.size,
                index,
                count,
                encoded.copyOfRange(start, end),
            )
        }
    }
}

internal fun customEmojiAssetChunkValidationError(chunk: CustomEmojiAssetChunk): String? {
    if (chunk.totalBytes !in 1..CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES) {
        return "Invalid custom emoji transfer size"
    }
    val expectedCount = (chunk.totalBytes + CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES - 1) /
        CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
    if (chunk.count != expectedCount) {
        return "Invalid custom emoji chunk count"
    }
    val expectedLength = if (chunk.index == chunk.count - 1) {
        chunk.totalBytes - chunk.index * CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
    } else {
        CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
    }
    if (chunk.dataLength != expectedLength) {
        return "Invalid custom emoji chunk length"
    }
    return null
}

internal fun validateCustomEmojiAssetChunk(chunk: CustomEmojiAssetChunk) {
    val message = customEmojiAssetChunkValidationError(chunk) ?: return
    throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, message)
}

class CustomEmojiAssetChunkCache(
    private val maximumEntries: Int = 64,
    private val maximumDataBytes: Int = 8 * 1_024 * 1_024,
) {
    private val entries = LinkedHashMap<CustomEmojiId, CachedChunks>(
        maximumEntries * 4 / 3 + 1,
        0.75f,
        true,
    )
    private var dataBytes = 0

    init {
        require(maximumEntries > 0) { "Maximum custom emoji cache entries must be positive: $maximumEntries" }
        require(maximumDataBytes >= CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES) {
            "Custom emoji cache must fit one maximum encoded asset: $maximumDataBytes"
        }
    }

    fun chunks(asset: CustomEmojiAsset): List<CustomEmojiAssetChunk> {
        entries[asset.id]?.let { cached -> return cached.chunks }
        val chunks = java.util.List.copyOf(CustomEmojiAssetChunker.split(asset))
        val bytes = chunks.sumOf(CustomEmojiAssetChunk::dataLength)
        entries.put(asset.id, CachedChunks(chunks, bytes))?.let { replaced ->
            dataBytes -= replaced.dataBytes
        }
        dataBytes += bytes
        evict()
        return chunks
    }

    fun clear() {
        entries.clear()
        dataBytes = 0
    }

    private fun evict() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maximumEntries || dataBytes > maximumDataBytes)) {
            dataBytes -= iterator.next().value.dataBytes
            iterator.remove()
        }
    }

    private data class CachedChunks(
        val chunks: List<CustomEmojiAssetChunk>,
        val dataBytes: Int,
    )
}

class CustomEmojiAssetAssembler(
    private val timeoutMillis: Long = 10_000,
) {
    private var state: AssemblyState? = null

    init {
        require(timeoutMillis > 0) { "Custom emoji assembly timeout must be positive: $timeoutMillis" }
    }

    fun accept(chunk: CustomEmojiAssetChunk, nowMillis: Long): CustomEmojiAsset? =
        acceptAssembly(chunk, nowMillis)?.asset

    fun acceptAssembly(chunk: CustomEmojiAssetChunk, nowMillis: Long): CustomEmojiAssetAssembly? =
        acceptEncodedAssembly(chunk, nowMillis)?.verify()

    fun acceptEncodedAssembly(chunk: CustomEmojiAssetChunk, nowMillis: Long): CustomEmojiEncodedAssembly? {
        customEmojiAssetChunkValidationError(chunk)?.let { message ->
            state = null
            invalid(message)
        }
        if (chunk.index == 0) {
            try {
                CustomEmojiLosslessCodec.preflightFirstChunk(chunk)
            } catch (exception: WireDecodeException) {
                state = null
                throw exception
            }
            state = AssemblyState(chunk, nowMillis)
        }
        val current = state ?: invalid("Custom emoji transfer did not start with its first chunk")
        if (nowMillis < current.startedMillis || nowMillis - current.startedMillis > timeoutMillis) {
            state = null
            invalid("Custom emoji transfer expired")
        }
        if (
            chunk.customEmojiId != current.id ||
            chunk.totalBytes != current.totalBytes ||
            chunk.count != current.chunkCount ||
            chunk.index != current.nextIndex
        ) {
            state = null
            invalid("Custom emoji chunks are inconsistent or out of order")
        }
        if (current.encodedBytes + chunk.dataLength > current.totalBytes) {
            state = null
            invalid("Custom emoji chunks exceed the declared transfer size")
        }
        chunk.copyDataTo(current.encoded, current.encodedBytes)
        current.encodedBytes += chunk.dataLength
        current.chunks += chunk
        current.nextIndex++
        if (current.nextIndex != current.chunkCount) {
            return null
        }
        state = null
        if (current.encodedBytes != current.totalBytes) {
            invalid("Custom emoji transfer ended before its declared size")
        }
        return CustomEmojiEncodedAssembly(
            current.id,
            current.encoded,
            current.chunks,
        )
    }

    fun tryAcceptEncodedAssembly(
        chunk: CustomEmojiAssetChunk,
        nowMillis: Long,
    ): CustomEmojiEncodedAssemblyResult = try {
        acceptEncodedAssembly(chunk, nowMillis)?.let(CustomEmojiEncodedAssemblyResult::Completed)
            ?: CustomEmojiEncodedAssemblyResult.Pending
    } catch (exception: WireDecodeException) {
        reset()
        CustomEmojiEncodedAssemblyResult.Rejected(exception.violation)
    }

    fun tryAcceptAssembly(chunk: CustomEmojiAssetChunk, nowMillis: Long): CustomEmojiAssetAssemblyResult = try {
        acceptAssembly(chunk, nowMillis)?.let(CustomEmojiAssetAssemblyResult::Completed)
            ?: CustomEmojiAssetAssemblyResult.Pending
    } catch (exception: WireDecodeException) {
        reset()
        CustomEmojiAssetAssemblyResult.Rejected(exception.violation)
    }

    fun reset() {
        state = null
    }

    private class AssemblyState(
        val id: CustomEmojiId,
        val totalBytes: Int,
        val chunkCount: Int,
        val startedMillis: Long,
        val encoded: ByteArray,
        val chunks: ArrayList<CustomEmojiAssetChunk>,
        var nextIndex: Int,
        var encodedBytes: Int,
    ) {
        constructor(
            chunk: CustomEmojiAssetChunk,
            startedMillis: Long,
        ) : this(
            chunk.customEmojiId,
            chunk.totalBytes,
            chunk.count,
            startedMillis,
            ByteArray(chunk.totalBytes),
            ArrayList(chunk.count),
            0,
            0,
        )
    }

    private fun invalid(message: String): Nothing =
        throw WireDecodeException(WireDecodeViolation.INVALID_CUSTOM_EMOJI, message)
}

sealed interface CustomEmojiEncodedAssemblyResult {
    data object Pending : CustomEmojiEncodedAssemblyResult

    data class Completed(
        val assembly: CustomEmojiEncodedAssembly,
    ) : CustomEmojiEncodedAssemblyResult

    data class Rejected(
        val violation: WireDecodeViolation,
    ) : CustomEmojiEncodedAssemblyResult
}

class CustomEmojiEncodedAssembly internal constructor(
    val customEmojiId: CustomEmojiId,
    private val encoded: ByteArray,
    chunks: List<CustomEmojiAssetChunk>,
) {
    val chunks: List<CustomEmojiAssetChunk> = java.util.List.copyOf(chunks)

    fun verify(): CustomEmojiAssetAssembly = CustomEmojiAssetAssembly(
        CustomEmojiLosslessCodec.decode(customEmojiId, encoded),
        chunks,
    )

    fun tryVerify(): CustomEmojiAssetVerificationResult = try {
        CustomEmojiAssetVerificationResult.Verified(verify())
    } catch (exception: WireDecodeException) {
        CustomEmojiAssetVerificationResult.Rejected(exception.violation)
    }
}

sealed interface CustomEmojiAssetVerificationResult {
    data class Verified(
        val assembly: CustomEmojiAssetAssembly,
    ) : CustomEmojiAssetVerificationResult

    data class Rejected(
        val violation: WireDecodeViolation,
    ) : CustomEmojiAssetVerificationResult
}

sealed interface CustomEmojiAssetAssemblyResult {
    data object Pending : CustomEmojiAssetAssemblyResult

    data class Completed(
        val assembly: CustomEmojiAssetAssembly,
    ) : CustomEmojiAssetAssemblyResult

    data class Rejected(
        val violation: WireDecodeViolation,
    ) : CustomEmojiAssetAssemblyResult
}

class CustomEmojiAssetAssembly(
    val asset: CustomEmojiAsset,
    chunks: List<CustomEmojiAssetChunk>,
) {
    val chunks: List<CustomEmojiAssetChunk> = java.util.List.copyOf(chunks)

    init {
        require(this.chunks.isNotEmpty()) { "A completed custom emoji assembly must contain chunks" }
        val first = this.chunks.first()
        val expectedCount = (first.totalBytes + CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES - 1) /
            CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
        require(first.totalBytes in 1..CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES) {
            "A completed custom emoji assembly exceeds encoded limits"
        }
        require(first.count == expectedCount && this.chunks.size == expectedCount) {
            "A completed custom emoji assembly has an invalid chunk count"
        }
        this.chunks.forEachIndexed { index, chunk ->
            val expectedLength = if (index == expectedCount - 1) {
                first.totalBytes - index * CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
            } else {
                CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
            }
            require(
                chunk.customEmojiId == asset.id &&
                    chunk.totalBytes == first.totalBytes &&
                    chunk.index == index &&
                    chunk.count == expectedCount &&
                    chunk.dataLength == expectedLength,
            ) {
                "A completed custom emoji assembly contains inconsistent chunks"
            }
        }
    }
}
