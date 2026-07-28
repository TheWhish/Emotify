package me.whish.emotify.network.payload

import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import java.nio.charset.StandardCharsets
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

internal abstract class BoundedPayloadCodec<T : Any>(
    private val maxBodyBytes: Int,
) : StreamCodec<FriendlyByteBuf, T> {
    init {
        require(maxBodyBytes > 0) { "Payload body limit must be positive: $maxBodyBytes" }
    }

    final override fun encode(buffer: FriendlyByteBuf, value: T) {
        val initialWriterIndex = buffer.writerIndex()
        try {
            encodeBody(buffer, value)
            if (buffer.writerIndex() - initialWriterIndex > maxBodyBytes) {
                throw EncoderException("Emotify payload exceeds $maxBodyBytes bytes")
            }
        } catch (exception: RuntimeException) {
            buffer.writerIndex(initialWriterIndex)
            throw exception
        }
    }

    final override fun decode(buffer: FriendlyByteBuf): T {
        if (buffer.readableBytes() > maxBodyBytes) {
            throw DecoderException("Emotify payload exceeds $maxBodyBytes bytes")
        }

        return try {
            val value = decodeBody(buffer)
            if (buffer.isReadable) {
                throw DecoderException("Emotify payload contains trailing bytes")
            }
            value
        } catch (exception: DecoderException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw DecoderException("Invalid Emotify payload", exception)
        }
    }

    protected abstract fun encodeBody(buffer: FriendlyByteBuf, value: T)

    protected abstract fun decodeBody(buffer: FriendlyByteBuf): T

    protected fun FriendlyByteBuf.writeCapabilities(capabilities: ProtocolCapabilities) {
        writeByte(capabilities.version.major)
        writeByte(capabilities.version.minor)
        writeVarLong(capabilities.features.bits)
    }

    protected fun FriendlyByteBuf.readCapabilities(): ProtocolCapabilities = ProtocolCapabilities(
        version = ProtocolVersion(readUnsignedByte().toInt(), readUnsignedByte().toInt()),
        features = FeatureFlags(readCanonicalVarLong()),
    )

    protected fun FriendlyByteBuf.writeEmotionId(id: EmotionId) {
        writeVarInt(id.value.length)
        writeCharSequence(id.value, StandardCharsets.US_ASCII)
    }

    protected fun FriendlyByteBuf.readEmotionId(): EmotionId {
        val length = readCanonicalVarInt()
        if (length !in 3..EmotionId.MAX_ENCODED_LENGTH) {
            throw DecoderException("Emotion ID length is outside protocol limits: $length")
        }
        val encoded = ByteArray(length)
        readBytes(encoded)
        return EmotionId.parse(encoded.toString(Charsets.US_ASCII))
            ?: throw DecoderException("Emotion ID is invalid")
    }

    protected fun FriendlyByteBuf.readCatalogIds(): List<EmotionId> {
        val count = readCanonicalVarInt()
        if (count !in 0..me.whish.emotify.domain.EmotionCatalog.MAX_SIZE) {
            throw DecoderException("Emotion catalog size is outside protocol limits: $count")
        }
        return List(count) { readEmotionId() }
    }

    protected fun FriendlyByteBuf.readCanonicalVarInt(): Int {
        var result = 0
        for (index in 0 until MAX_VAR_INT_BYTES) {
            val current = readUnsignedByte().toInt()
            if (index == MAX_VAR_INT_BYTES - 1 && current and 0xF0 != 0) {
                throw DecoderException("VarInt exceeds 32 bits")
            }
            result = result or ((current and 0x7F) shl (index * 7))
            if (current and 0x80 == 0) {
                if (index + 1 != varIntSize(result)) {
                    throw DecoderException("VarInt is not canonical")
                }
                return result
            }
        }
        throw DecoderException("VarInt is too long")
    }

    protected fun FriendlyByteBuf.readCanonicalVarLong(): Long {
        var result = 0L
        for (index in 0 until MAX_VAR_LONG_BYTES) {
            val current = readUnsignedByte().toInt()
            if (index == MAX_VAR_LONG_BYTES - 1 && current and 0xFE != 0) {
                throw DecoderException("VarLong exceeds 64 bits")
            }
            result = result or ((current and 0x7F).toLong() shl (index * 7))
            if (current and 0x80 == 0) {
                if (index + 1 != varLongSize(result)) {
                    throw DecoderException("VarLong is not canonical")
                }
                return result
            }
        }
        throw DecoderException("VarLong is too long")
    }

    private fun varIntSize(value: Int): Int {
        var remaining = value
        var size = 1
        while (remaining and -128 != 0) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    private fun varLongSize(value: Long): Int {
        var remaining = value
        var size = 1
        while (remaining and -128L != 0L) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    companion object {
        private const val MAX_VAR_INT_BYTES = 5
        private const val MAX_VAR_LONG_BYTES = 10
    }
}
