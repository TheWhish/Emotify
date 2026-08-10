package me.whish.emotify.wire.v1

interface WireCodec<T : Any> {
    val maxBodyBytes: Int

    fun encodedSize(value: T): Int

    fun encode(writer: WireWriter, value: T)

    fun encodeToByteArray(value: T): ByteArray

    fun decode(reader: WireReader): T

    fun decode(source: ByteArray): T
}

internal abstract class BoundedWireCodec<T : Any>(
    final override val maxBodyBytes: Int,
) : WireCodec<T> {
    init {
        require(maxBodyBytes > 0) { "Protocol 1 body limit must be positive: $maxBodyBytes" }
    }

    final override fun encodedSize(value: T): Int = checkedEncodedSize(
        prepareEncoding(value)?.encodedSize ?: computeEncodedSize(value),
    )

    final override fun encode(writer: WireWriter, value: T) {
        val prepared = prepareEncoding(value)
        encode(writer, value, checkedEncodedSize(prepared?.encodedSize ?: computeEncodedSize(value)), prepared)
    }

    final override fun encodeToByteArray(value: T): ByteArray {
        val prepared = prepareEncoding(value)
        val size = checkedEncodedSize(prepared?.encodedSize ?: computeEncodedSize(value))
        val writer = ByteArrayWireWriter(size)
        encode(writer, value, size, prepared)
        return writer.toByteArray()
    }

    private fun checkedEncodedSize(size: Int): Int {
        if (size > maxBodyBytes) {
            throw WireEncodeException(
                WireEncodeViolation.BODY_TOO_LARGE,
                "Protocol 1 payload exceeds $maxBodyBytes bytes",
            )
        }
        return size
    }

    private fun encode(
        writer: WireWriter,
        value: T,
        expectedSize: Int,
        prepared: PreparedWireEncoding?,
    ) {
        val initialPosition = writer.position
        try {
            if (prepared == null) {
                encodeBody(writer, value)
            } else {
                prepared.encode(writer)
            }
            val actualSize = writer.position - initialPosition
            if (actualSize != expectedSize) {
                throw WireEncodeException(
                    WireEncodeViolation.ENCODED_SIZE_MISMATCH,
                    "Protocol 1 codec wrote $actualSize bytes instead of $expectedSize",
                )
            }
        } catch (exception: RuntimeException) {
            writer.reset(initialPosition)
            throw exception
        }
    }

    final override fun decode(reader: WireReader): T {
        val initialPosition = reader.position
        if (reader.remainingBytes > maxBodyBytes) {
            throw WireDecodeException(
                WireDecodeViolation.BODY_TOO_LARGE,
                "Protocol 1 payload exceeds $maxBodyBytes bytes",
            )
        }
        return try {
            val value = decodeBody(reader)
            if (reader.remainingBytes != 0) {
                throw WireDecodeException(
                    WireDecodeViolation.TRAILING_BYTES,
                    "Protocol 1 payload contains trailing bytes",
                )
            }
            value
        } catch (exception: WireDecodeException) {
            reader.reset(initialPosition)
            throw exception
        } catch (exception: RuntimeException) {
            reader.reset(initialPosition)
            throw WireDecodeException(
                WireDecodeViolation.INVALID_FIELD_VALUE,
                "Protocol 1 payload contains an invalid field value",
                exception,
            )
        }
    }

    final override fun decode(source: ByteArray): T = decode(ByteArrayWireReader(source))

    protected abstract fun computeEncodedSize(value: T): Int

    protected abstract fun encodeBody(writer: WireWriter, value: T)

    protected open fun prepareEncoding(value: T): PreparedWireEncoding? = null

    protected abstract fun decodeBody(reader: WireReader): T
}

internal interface PreparedWireEncoding {
    val encodedSize: Int

    fun encode(writer: WireWriter)
}
