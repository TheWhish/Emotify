package me.whish.emotify.network.payload

import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import me.whish.emotify.wire.v1.WireCodec
import me.whish.emotify.wire.v1.WireDecodeException
import me.whish.emotify.wire.v1.WireDecodeViolation
import me.whish.emotify.wire.v1.WireEncodeException
import me.whish.emotify.wire.v1.WireEncodeViolation
import me.whish.emotify.wire.v1.WireReader
import me.whish.emotify.wire.v1.WireWriter
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

internal class ProtocolV1PayloadCodec<TPayload : Any, TMessage : Any>(
    private val wireCodec: WireCodec<TMessage>,
    private val unwrap: (TPayload) -> TMessage,
    private val wrap: (TMessage) -> TPayload,
    private val maxOutboundBodyBytes: Int = wireCodec.maxBodyBytes,
) : StreamCodec<FriendlyByteBuf, TPayload> {
    init {
        require(maxOutboundBodyBytes in 1..wireCodec.maxBodyBytes) {
            "Outbound body limit must be within the Protocol 1 codec limit: $maxOutboundBodyBytes"
        }
    }

    override fun encode(buffer: FriendlyByteBuf, value: TPayload) {
        val initialWriterIndex = buffer.writerIndex()
        try {
            val message = unwrap(value)
            val encodedSize = wireCodec.encodedSize(message)
            if (encodedSize > maxOutboundBodyBytes) {
                throw WireEncodeException(
                    WireEncodeViolation.BODY_TOO_LARGE,
                    "Protocol 1 outbound payload exceeds $maxOutboundBodyBytes bytes",
                )
            }
            wireCodec.encode(FriendlyByteBufWireWriter(buffer), message)
        } catch (exception: WireEncodeException) {
            buffer.writerIndex(initialWriterIndex)
            val message = if (exception.violation == WireEncodeViolation.BODY_TOO_LARGE) {
                "Emotify payload exceeds $maxOutboundBodyBytes bytes"
            } else {
                exception.message
            }
            throw EncoderException(message, exception)
        } catch (exception: RuntimeException) {
            buffer.writerIndex(initialWriterIndex)
            throw EncoderException("Invalid Emotify Protocol 1 payload", exception)
        }
    }

    override fun decode(buffer: FriendlyByteBuf): TPayload = try {
        wrap(wireCodec.decode(FriendlyByteBufWireReader(buffer)))
    } catch (exception: WireDecodeException) {
        val message = if (exception.violation == WireDecodeViolation.BODY_TOO_LARGE) {
            "Emotify payload exceeds ${wireCodec.maxBodyBytes} bytes"
        } else {
            exception.message
        }
        throw DecoderException(message, exception)
    } catch (exception: RuntimeException) {
        throw DecoderException("Invalid Emotify Protocol 1 payload", exception)
    }
}

private class FriendlyByteBufWireReader(
    private val buffer: FriendlyByteBuf,
) : WireReader {
    override val position: Int
        get() = buffer.readerIndex()

    override val remainingBytes: Int
        get() = buffer.readableBytes()

    override fun readUnsignedByte(): Int = try {
        buffer.readUnsignedByte().toInt()
    } catch (exception: IndexOutOfBoundsException) {
        throw WireDecodeException(
            WireDecodeViolation.TRUNCATED_BODY,
            "Protocol 1 payload is truncated",
            exception,
        )
    }

    override fun reset(position: Int) {
        buffer.readerIndex(position)
    }
}

private class FriendlyByteBufWireWriter(
    private val buffer: FriendlyByteBuf,
) : WireWriter {
    override val position: Int
        get() = buffer.writerIndex()

    override fun writeUnsignedByte(value: Int) {
        if (value !in 0..255) {
            throw WireEncodeException(WireEncodeViolation.UNENCODABLE_VALUE, "Wire byte must fit U8: $value")
        }
        buffer.writeByte(value)
    }

    override fun reset(position: Int) {
        buffer.writerIndex(position)
    }
}
