package me.whish.emotify.client

import com.mojang.blaze3d.platform.NativeImage
import java.io.IOException
import java.nio.ByteBuffer
import me.whish.emotify.client.custom.CustomEmojiFile
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.client.custom.GifTimelineNormalizer
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

internal class CustomEmojiFrameLimitExceededException(maximumFrameCount: Int) : IllegalArgumentException(
    "GIF contains more than $maximumFrameCount source frames",
)

class DecodedCustomEmoji private constructor(
    frames: List<NativeImage>,
    durationsMillis: IntArray,
) : AutoCloseable {
    val frames: List<NativeImage> = java.util.List.copyOf(frames)
    private val durationsMillis = durationsMillis.copyOf()

    val frameCount: Int
        get() = frames.size

    init {
        require(this.frames.isNotEmpty()) { "Decoded custom emoji must contain at least one frame" }
        require(this.frames.size == this.durationsMillis.size) { "Every decoded custom emoji frame requires a duration" }
    }

    fun durationMillisAt(index: Int): Int = durationsMillis[index]

    override fun close() {
        frames.forEach(NativeImage::close)
    }

    companion object {
        fun static(image: NativeImage): DecodedCustomEmoji = DecodedCustomEmoji(listOf(image), intArrayOf(0))

        fun animated(frames: List<NativeImage>, durationsMillis: IntArray): DecodedCustomEmoji =
            DecodedCustomEmoji(frames, durationsMillis)
    }
}

object CustomEmojiImageDecoder {
    fun decode(file: CustomEmojiFile, bytes: ByteArray): DecodedCustomEmoji = when (file.format) {
        CustomEmojiFileFormat.PNG -> DecodedCustomEmoji.static(NativeImage.read(bytes))
        CustomEmojiFileFormat.JPEG -> DecodedCustomEmoji.static(decodeJpeg(bytes))
        CustomEmojiFileFormat.GIF -> decodeGif(bytes, file.sourceSize)
    }

    private fun decodeJpeg(bytes: ByteArray): NativeImage {
        val encoded = MemoryUtil.memAlloc(bytes.size)
        try {
            encoded.put(bytes).flip()
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val decoded = STBImage.stbi_load_from_memory(
                    encoded,
                    width,
                    height,
                    channels,
                    RGBA_CHANNELS,
                ) ?: throw IOException(
                    "Could not decode JPEG: ${STBImage.stbi_failure_reason() ?: "unknown STB failure"}",
                )
                try {
                    return decoded.toNativeImage(width[0], height[0], 0)
                } finally {
                    STBImage.stbi_image_free(decoded)
                }
            }
        } finally {
            MemoryUtil.memFree(encoded)
        }
    }

    private fun decodeGif(bytes: ByteArray, expectedSize: Int): DecodedCustomEmoji {
        val inspected = GifStructure.inspect(bytes)
        require(inspected.width == expectedSize && inspected.height == expectedSize) {
            "GIF dimensions changed during decode: ${inspected.width}x${inspected.height}"
        }
        val encoded = MemoryUtil.memAlloc(bytes.size)
        try {
            encoded.put(bytes).flip()
            MemoryStack.stackPush().use { stack ->
                val delaysPointer = stack.mallocPointer(1)
                delaysPointer.put(0, 0L)
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val frameCount = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val decoded = STBImage.stbi_load_gif_from_memory(
                    encoded,
                    delaysPointer,
                    width,
                    height,
                    frameCount,
                    channels,
                    RGBA_CHANNELS,
                ) ?: throw IOException(
                    "Could not decode GIF: ${STBImage.stbi_failure_reason() ?: "unknown STB failure"}",
                )
                val nativeDelays = delaysPointer[0]
                try {
                    require(width[0] == expectedSize && height[0] == expectedSize) {
                        "Decoded GIF dimensions changed during load: ${width[0]}x${height[0]}"
                    }
                    require(frameCount[0] == inspected.frameCount) {
                        "Decoded GIF frame count does not match its structure: ${frameCount[0]}"
                    }
                    return decoded.toAnimatedEmoji(width[0], height[0], frameCount[0], nativeDelays)
                } finally {
                    STBImage.stbi_image_free(decoded)
                    if (nativeDelays != 0L) {
                        STBImage.nstbi_image_free(nativeDelays)
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(encoded)
        }
    }

    private fun ByteBuffer.toAnimatedEmoji(
        width: Int,
        height: Int,
        frameCount: Int,
        nativeDelays: Long,
    ): DecodedCustomEmoji {
        if (frameCount == 1) {
            return DecodedCustomEmoji.static(toNativeImage(width, height, 0))
        }
        require(nativeDelays != 0L) { "Decoded GIF does not contain frame delays" }
        val sourceDelays = MemoryUtil.memIntBuffer(nativeDelays, frameCount)
        val normalized = GifTimelineNormalizer.normalize(
            IntArray(frameCount) { index -> sourceDelays[index] },
        ) { left, right ->
            framePixelContentEquals(width, height, left, right)
        }
        val frames = ArrayList<NativeImage>(normalized.size)
        val durationsMillis = IntArray(normalized.size)
        try {
            normalized.forEachIndexed { index, frame ->
                frames += toNativeImage(
                    width,
                    height,
                    frame.sourceIndex * width * height * RGBA_CHANNELS,
                )
                durationsMillis[index] = frame.durationMillis
            }
            return if (frames.size == 1) {
                DecodedCustomEmoji.static(frames.single())
            } else {
                DecodedCustomEmoji.animated(frames, durationsMillis)
            }
        } catch (failure: Throwable) {
            frames.forEach(NativeImage::close)
            throw failure
        }
    }

    private fun ByteBuffer.framePixelContentEquals(
        width: Int,
        height: Int,
        leftFrameIndex: Int,
        rightFrameIndex: Int,
    ): Boolean {
        val frameByteLength = width * height * RGBA_CHANNELS
        val leftOffset = leftFrameIndex * frameByteLength
        val rightOffset = rightFrameIndex * frameByteLength
        repeat(frameByteLength) { offset ->
            if (get(leftOffset + offset) != get(rightOffset + offset)) {
                return false
            }
        }
        return true
    }

    private fun ByteBuffer.toNativeImage(width: Int, height: Int, baseOffset: Int): NativeImage {
        val image = NativeImage(width, height, true)
        try {
            repeat(width * height) { index ->
                val offset = baseOffset + index * RGBA_CHANNELS
                image.setPixelABGR(
                    index % width,
                    index / width,
                    rgba(
                        get(offset).unsigned(),
                        get(offset + 1).unsigned(),
                        get(offset + 2).unsigned(),
                        get(offset + 3).unsigned(),
                    ),
                )
            }
            return image
        } catch (failure: Throwable) {
            image.close()
            throw failure
        }
    }

    private fun rgba(red: Int, green: Int, blue: Int, alpha: Int): Int =
        alpha shl 24 or (blue shl 16) or (green shl 8) or red

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val RGBA_CHANNELS = 4
}

private data class GifStructure(
    val width: Int,
    val height: Int,
    val frameCount: Int,
) {
    companion object {
        fun inspect(bytes: ByteArray): GifStructure {
            require(bytes.size >= LOGICAL_SCREEN_END) { "GIF header is truncated" }
            require(bytes.matchesAscii(0, "GIF87a") || bytes.matchesAscii(0, "GIF89a")) { "Invalid GIF signature" }
            val width = bytes.unsignedShortLittleEndian(6)
            val height = bytes.unsignedShortLittleEndian(8)
            require(width > 0 && height > 0) { "GIF dimensions must be positive" }
            var position = LOGICAL_SCREEN_END
            val logicalPacked = bytes[10].unsigned()
            if (logicalPacked and COLOR_TABLE_FLAG != 0) {
                position = bytes.advanceColorTable(position, logicalPacked)
            }
            var frames = 0
            while (position < bytes.size) {
                when (bytes[position++].unsigned()) {
                    IMAGE_DESCRIPTOR -> {
                        require(position + IMAGE_DESCRIPTOR_BYTES <= bytes.size) { "GIF image descriptor is truncated" }
                        val left = bytes.unsignedShortLittleEndian(position)
                        val top = bytes.unsignedShortLittleEndian(position + 2)
                        val frameWidth = bytes.unsignedShortLittleEndian(position + 4)
                        val frameHeight = bytes.unsignedShortLittleEndian(position + 6)
                        val packed = bytes[position + 8].unsigned()
                        require(frameWidth > 0 && frameHeight > 0) { "GIF frame dimensions must be positive" }
                        require(left + frameWidth <= width && top + frameHeight <= height) {
                            "GIF frame exceeds its logical screen"
                        }
                        position += IMAGE_DESCRIPTOR_BYTES
                        if (packed and COLOR_TABLE_FLAG != 0) {
                            position = bytes.advanceColorTable(position, packed)
                        }
                        require(position < bytes.size) { "GIF image data is truncated" }
                        position++
                        position = bytes.skipSubBlocks(position)
                        frames++
                        if (frames > GifTimelineNormalizer.MAXIMUM_SOURCE_FRAME_COUNT) {
                            throw CustomEmojiFrameLimitExceededException(GifTimelineNormalizer.MAXIMUM_SOURCE_FRAME_COUNT)
                        }
                    }
                    EXTENSION_INTRODUCER -> {
                        require(position < bytes.size) { "GIF extension label is truncated" }
                        position++
                        position = bytes.skipSubBlocks(position)
                    }
                    TRAILER -> {
                        require(frames > 0) { "GIF does not contain an image frame" }
                        return GifStructure(width, height, frames)
                    }
                    else -> throw IllegalArgumentException("GIF contains an unknown block")
                }
            }
            throw IllegalArgumentException("GIF trailer is missing")
        }

        private fun ByteArray.advanceColorTable(position: Int, packed: Int): Int {
            val colorCount = 1 shl ((packed and COLOR_TABLE_SIZE_MASK) + 1)
            val next = position + colorCount * COLOR_BYTES
            require(next <= size) { "GIF color table is truncated" }
            return next
        }

        private fun ByteArray.skipSubBlocks(start: Int): Int {
            var position = start
            while (true) {
                require(position < size) { "GIF data sub-block is truncated" }
                val blockSize = this[position++].unsigned()
                if (blockSize == 0) {
                    return position
                }
                require(position + blockSize <= size) { "GIF data sub-block exceeds the file" }
                position += blockSize
            }
        }

        private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
            value.indices.all { index -> this[offset + index].unsigned() == value[index].code }

        private fun ByteArray.unsignedShortLittleEndian(offset: Int): Int =
            this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

        private fun Byte.unsigned(): Int = toInt() and 0xFF

        private const val LOGICAL_SCREEN_END = 13
        private const val IMAGE_DESCRIPTOR = 0x2C
        private const val EXTENSION_INTRODUCER = 0x21
        private const val TRAILER = 0x3B
        private const val IMAGE_DESCRIPTOR_BYTES = 9
        private const val COLOR_TABLE_FLAG = 0x80
        private const val COLOR_TABLE_SIZE_MASK = 0x07
        private const val COLOR_BYTES = 3
    }
}

