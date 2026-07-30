package me.whish.emotify.wire.v1

interface WireReader {
    val position: Int
    val remainingBytes: Int

    fun readUnsignedByte(): Int

    fun reset(position: Int)
}

interface WireWriter {
    val position: Int

    fun writeUnsignedByte(value: Int)

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
