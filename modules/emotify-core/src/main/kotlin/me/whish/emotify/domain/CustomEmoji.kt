package me.whish.emotify.domain

import java.security.MessageDigest

data class CustomEmojiId(
    val mostSignificantBits: Long,
    val middleBits: Long,
    val leastSignificantBits: Long,
) {
    val emotionId: EmotionId
        get() = EmotionId.of("$NAMESPACE:${hexValue()}")

    fun hexValue(): String = buildString(HEX_LENGTH) {
        appendHex(mostSignificantBits)
        appendHex(middleBits)
        appendHex(leastSignificantBits)
    }

    companion object {
        const val BYTE_LENGTH = 24
        const val HEX_LENGTH = BYTE_LENGTH * 2
        const val NAMESPACE = "emotify_custom"

        fun fromPixels(pixels: CustomEmojiPixels): CustomEmojiId {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(pixels.size.toByte())
            digest.updatePixels(pixels)
            return digest.toCustomEmojiId()
        }

        fun fromFrames(frames: List<CustomEmojiFrame>): CustomEmojiId {
            require(frames.size in 2..CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
                "Animated custom emoji must contain between 2 and ${CustomEmojiAsset.MAXIMUM_FRAME_COUNT} frames"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ANIMATED_CONTENT_FORMAT)
            digest.update(frames.first().pixels.size.toByte())
            digest.update(frames.size.toByte())
            frames.forEach { frame ->
                digest.updateInt(frame.durationMillis)
                digest.updatePixels(frame.pixels)
            }
            return digest.toCustomEmojiId()
        }

        fun parse(emotionId: EmotionId): CustomEmojiId? {
            val prefix = "$NAMESPACE:"
            val value = emotionId.value
            if (!value.startsWith(prefix) || value.length != prefix.length + HEX_LENGTH) {
                return null
            }
            val hex = value.substring(prefix.length)
            return try {
                CustomEmojiId(
                    hex.substring(0, 16).toULong(16).toLong(),
                    hex.substring(16, 32).toULong(16).toLong(),
                    hex.substring(32, 48).toULong(16).toLong(),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun ByteArray.readLong(offset: Int): Long {
            var result = 0L
            repeat(Long.SIZE_BYTES) { index ->
                result = result shl Byte.SIZE_BITS or (this[offset + index].toLong() and 0xFFL)
            }
            return result
        }

        private fun MessageDigest.updateInt(value: Int) {
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }

        private fun MessageDigest.updatePixels(pixels: CustomEmojiPixels) {
            repeat(pixels.pixelCount) { index -> updateInt(pixels.colorAt(index)) }
        }

        private fun MessageDigest.toCustomEmojiId(): CustomEmojiId {
            val bytes = digest()
            return CustomEmojiId(
                bytes.readLong(0),
                bytes.readLong(Long.SIZE_BYTES),
                bytes.readLong(Long.SIZE_BYTES * 2),
            )
        }

        private fun StringBuilder.appendHex(value: Long) {
            for (shift in 60 downTo 0 step 4) {
                append(HEX_DIGITS[(value ushr shift and 0xFL).toInt()])
            }
        }

        private const val HEX_DIGITS = "0123456789abcdef"
        private const val ANIMATED_CONTENT_FORMAT: Byte = 1
    }
}

class CustomEmojiPixels private constructor(
    val size: Int,
    private val colors: IntArray,
) {
    val width: Int
        get() = size

    val height: Int
        get() = size

    val pixelCount: Int
        get() = colors.size

    val rawByteLength: Int
        get() = pixelCount * Int.SIZE_BYTES

    fun colorAt(index: Int): Int {
        require(index in colors.indices) { "Custom emoji pixel index is outside ${size}x$size bounds: $index" }
        return colors[index]
    }

    fun copyColors(): IntArray = colors.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is CustomEmojiPixels && colors.contentEquals(other.colors)

    override fun hashCode(): Int = colors.contentHashCode()

    override fun toString(): String = "CustomEmojiPixels(${size}x$size)"

    companion object {
        const val MINIMUM_SIZE = 8
        const val MAXIMUM_SIZE = 128

        private val SUPPORTED_SIZES = setOf(MINIMUM_SIZE, 16, 32, 64, MAXIMUM_SIZE)

        fun of(colors: IntArray): CustomEmojiPixels {
            val size = when (colors.size) {
                8 * 8 -> 8
                16 * 16 -> 16
                32 * 32 -> 32
                64 * 64 -> 64
                128 * 128 -> 128
                else -> throw IllegalArgumentException("Custom emoji pixel count does not match a supported square size: ${colors.size}")
            }
            return of(size, colors)
        }

        fun of(size: Int, colors: IntArray): CustomEmojiPixels {
            require(size in SUPPORTED_SIZES) { "Custom emoji size must be 8, 16, 32, 64, or 128: $size" }
            require(colors.size == size * size) {
                "Custom emoji ${size}x$size must contain exactly ${size * size} pixels: ${colors.size}"
            }
            return CustomEmojiPixels(size, colors.copyOf())
        }

        fun supports(size: Int): Boolean = size in SUPPORTED_SIZES
    }
}

data class CustomEmojiFrame(
    val pixels: CustomEmojiPixels,
    val durationMillis: Int,
) {
    init {
        require(durationMillis == 0 || durationMillis in CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS..CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS) {
            "Custom emoji frame duration must be zero or between ${CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS} and ${CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS} ms: $durationMillis"
        }
    }
}

object RemoteCustomEmojiCacheLimits {
    const val MAXIMUM_ASSETS = 256
    const val MAXIMUM_RAW_BYTES = 16 * 1_024 * 1_024
}

object CustomEmojiTransferRateLimits {
    const val BURST_UNITS = 36
    const val REFILL_UNITS_PER_SECOND = 18
}

class RemoteCustomEmojiRetention(
    private val maximumAssets: Int = RemoteCustomEmojiCacheLimits.MAXIMUM_ASSETS,
    private val maximumRawBytes: Int = RemoteCustomEmojiCacheLimits.MAXIMUM_RAW_BYTES,
) {
    private val entries = LinkedHashMap<CustomEmojiId, Int>(maximumAssets * 4 / 3 + 1)
    private var retainedRawBytes = 0

    init {
        require(maximumAssets > 0) { "Maximum remote custom emoji count must be positive: $maximumAssets" }
        require(maximumRawBytes >= CustomEmojiAsset.MAXIMUM_RAW_BYTE_LENGTH) {
            "Remote custom emoji retention must fit one maximum asset: $maximumRawBytes"
        }
    }

    fun contains(id: CustomEmojiId): Boolean = entries.containsKey(id)

    fun retain(id: CustomEmojiId, rawByteLength: Int): List<CustomEmojiId> {
        require(rawByteLength in 1..CustomEmojiAsset.MAXIMUM_RAW_BYTE_LENGTH) {
            "Remote custom emoji retained byte size is invalid: $rawByteLength"
        }
        val previous = entries[id]
        if (previous != null) {
            require(previous == rawByteLength) { "A custom emoji content ID cannot change its retained byte size" }
            return emptyList()
        }
        entries[id] = rawByteLength
        retainedRawBytes += rawByteLength
        if (entries.size <= maximumAssets && retainedRawBytes <= maximumRawBytes) {
            return emptyList()
        }

        val evicted = ArrayList<CustomEmojiId>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maximumAssets || retainedRawBytes > maximumRawBytes)) {
            val entry = iterator.next()
            retainedRawBytes -= entry.value
            evicted += entry.key
            iterator.remove()
        }
        return java.util.List.copyOf(evicted)
    }

    fun clear() {
        entries.clear()
        retainedRawBytes = 0
    }
}

class CustomEmojiAsset private constructor(
    val id: CustomEmojiId,
    frames: List<CustomEmojiFrame>,
) {
    val frames: List<CustomEmojiFrame> = java.util.List.copyOf(frames)

    val pixels: CustomEmojiPixels
        get() = frames.first().pixels

    val isAnimated: Boolean
        get() = frames.size > 1

    val cycleDurationMillis: Int = frames.sumOf(CustomEmojiFrame::durationMillis)

    val rawByteLength: Int = frames.sumOf { frame -> frame.pixels.rawByteLength }

    override fun equals(other: Any?): Boolean =
        this === other || other is CustomEmojiAsset && id == other.id && frames == other.frames

    override fun hashCode(): Int = 31 * id.hashCode() + frames.hashCode()

    override fun toString(): String = "CustomEmojiAsset(id=$id, frames=${frames.size}, size=${pixels.size})"

    companion object {
        private const val MILLISECONDS_PER_SECOND = 1_000

        const val MAXIMUM_FRAME_COUNT = 30
        const val MAXIMUM_FRAMES_PER_SECOND = 15
        const val MINIMUM_FRAME_DURATION_MILLIS =
            (MILLISECONDS_PER_SECOND + MAXIMUM_FRAMES_PER_SECOND - 1) / MAXIMUM_FRAMES_PER_SECOND
        const val MAXIMUM_FRAME_DURATION_MILLIS = 2_000
        const val MAXIMUM_CYCLE_DURATION_MILLIS = 2_200
        const val MAXIMUM_ANIMATED_SIZE = 64
        const val MAXIMUM_RAW_BYTE_LENGTH = MAXIMUM_FRAME_COUNT * MAXIMUM_ANIMATED_SIZE * MAXIMUM_ANIMATED_SIZE * Int.SIZE_BYTES

        fun create(pixels: CustomEmojiPixels): CustomEmojiAsset =
            CustomEmojiAsset(CustomEmojiId.fromPixels(pixels), listOf(CustomEmojiFrame(pixels, 0)))

        fun create(frames: List<CustomEmojiFrame>): CustomEmojiAsset {
            val canonical = validateFrames(frames)
            return if (canonical.size == 1) {
                create(canonical.first().pixels)
            } else {
                CustomEmojiAsset(CustomEmojiId.fromFrames(canonical), canonical)
            }
        }

        fun verify(id: CustomEmojiId, pixels: CustomEmojiPixels): CustomEmojiAsset? =
            CustomEmojiAsset(id, listOf(CustomEmojiFrame(pixels, 0))).takeIf { CustomEmojiId.fromPixels(pixels) == id }

        fun verify(id: CustomEmojiId, frames: List<CustomEmojiFrame>): CustomEmojiAsset? {
            return try {
                val canonical = validateFrames(frames)
                val expected = if (canonical.size == 1) {
                    CustomEmojiId.fromPixels(canonical.first().pixels)
                } else {
                    CustomEmojiId.fromFrames(canonical)
                }
                CustomEmojiAsset(id, canonical).takeIf { expected == id }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun validateFrames(frames: List<CustomEmojiFrame>): List<CustomEmojiFrame> {
            require(frames.size in 1..MAXIMUM_FRAME_COUNT) {
                "Custom emoji must contain between 1 and $MAXIMUM_FRAME_COUNT frames: ${frames.size}"
            }
            val canonical = java.util.List.copyOf(frames)
            val size = canonical.first().pixels.size
            require(canonical.all { frame -> frame.pixels.size == size }) {
                "Every custom emoji frame must use the same dimensions"
            }
            if (canonical.size == 1) {
                require(canonical.first().durationMillis == 0) { "A static custom emoji frame must have zero duration" }
                return canonical
            }
            require(size <= MAXIMUM_ANIMATED_SIZE) {
                "Animated custom emoji dimensions cannot exceed ${MAXIMUM_ANIMATED_SIZE}x$MAXIMUM_ANIMATED_SIZE: ${size}x$size"
            }
            require(canonical.all { frame -> frame.durationMillis >= MINIMUM_FRAME_DURATION_MILLIS }) {
                "Animated custom emoji frames must have a positive bounded duration"
            }
            val cycleDuration = canonical.sumOf(CustomEmojiFrame::durationMillis)
            require(cycleDuration <= MAXIMUM_CYCLE_DURATION_MILLIS) {
                "Custom emoji animation cycle exceeds $MAXIMUM_CYCLE_DURATION_MILLIS ms: $cycleDuration"
            }
            return canonical
        }
    }
}
