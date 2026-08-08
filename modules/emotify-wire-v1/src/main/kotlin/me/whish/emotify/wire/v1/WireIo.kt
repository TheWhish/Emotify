package me.whish.emotify.wire.v1

interface WireReader {
    val position: Int
    val remainingBytes: Int

    fun readUnsignedByte(): Int

    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Wire byte count must not be negative: $length" }
        return ByteArray(length) { readUnsignedByte().toByte() }
    }

    fun reset(position: Int)
}

interface WireWriter {
    val position: Int

    fun writeUnsignedByte(value: Int)

    fun writeBytes(source: ByteArray) {
        source.forEach { value -> writeUnsignedByte(value.toInt() and 0xFF) }
    }

    fun reset(position: Int)
}

internal class ByteArrayWireReader(
    private val source: ByteArray,
) : WireReader {
    private var currentPosition = 0

    override val position: Int
        get() = currentPosition

    override val remainingBytes: Int
        get() = source.size - currentPosition

    override fun readUnsignedByte(): Int {
        if (currentPosition >= source.size) {
            throw WireDecodeException(WireDecodeViolation.TRUNCATED_BODY, "Protocol 1 payload is truncated")
        }
        return source[currentPosition++].toInt() and 0xFF
    }

    override fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Wire byte count must not be negative: $length" }
        if (length > remainingBytes) {
            throw WireDecodeException(WireDecodeViolation.TRUNCATED_BODY, "Protocol 1 payload is truncated")
        }
        return source.copyOfRange(currentPosition, currentPosition + length).also {
            currentPosition += length
        }
    }

    override fun reset(position: Int) {
        require(position in 0..source.size) { "Reader position is outside the source: $position" }
        currentPosition = position
    }
}

internal class ByteArrayWireWriter(
    capacity: Int,
) : WireWriter {
    private val destination = ByteArray(capacity)
    private var currentPosition = 0

    override val position: Int
        get() = currentPosition

    override fun writeUnsignedByte(value: Int) {
        require(value in 0..255) { "Wire byte must fit U8: $value" }
        if (currentPosition >= destination.size) {
            throw WireEncodeException(
                WireEncodeViolation.DESTINATION_EXHAUSTED,
                "Protocol 1 destination is exhausted",
            )
        }
        destination[currentPosition++] = value.toByte()
    }

    override fun writeBytes(source: ByteArray) {
        if (source.size > destination.size - currentPosition) {
            throw WireEncodeException(
                WireEncodeViolation.DESTINATION_EXHAUSTED,
                "Protocol 1 destination is exhausted",
            )
        }
        source.copyInto(destination, currentPosition)
        currentPosition += source.size
    }

    override fun reset(position: Int) {
        require(position in 0..currentPosition) { "Writer position cannot advance during reset: $position" }
        currentPosition = position
    }

    fun toByteArray(): ByteArray = if (currentPosition == destination.size) {
        destination
    } else {
        destination.copyOf(currentPosition)
    }
}
