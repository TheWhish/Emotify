package me.whish.emotify.network.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf

@Suppress("unused")
class HelloPayloadCodecTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val serverPayload = ServerHelloPayload(
        ServerHello(capabilities, 1_200, BuiltInEmotionCatalog.catalog),
    )
    val clientPayload = ClientHelloPayload(ClientHello(capabilities))

    test("server hello round trips through its wire codec") {
        roundTrip(ServerHelloPayload.STREAM_CODEC, serverPayload) shouldBe serverPayload
    }

    test("complete built in catalog remains compact during handshake") {
        val buffer = encoded(ServerHelloPayload.STREAM_CODEC, serverPayload)
        try {
            (buffer.readableBytes() < 4_096) shouldBe true
            ServerHelloPayload.STREAM_CODEC.decode(buffer) shouldBe serverPayload
        } finally {
            buffer.release()
        }
    }

    test("client hello round trips through its wire codec") {
        roundTrip(ClientHelloPayload.STREAM_CODEC, clientPayload) shouldBe clientPayload
    }

    test("server hello decoder accepts a three hundred entry legacy catalog") {
        val catalog = EmotionCatalog.of(
            List(300) { index -> EmotionId.of("emotify:emotion_$index") },
        )
        val payload = ServerHelloPayload(ServerHello(capabilities, 1_200, catalog))

        val encoded = ProtocolV1Codecs.serverHello.encodeToByteArray(payload.envelope)
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(encoded))
        try {
            ServerHelloPayload.STREAM_CODEC.decode(buffer) shouldBe payload
        } finally {
            buffer.release()
        }
    }

    test("server hello decoder accepts its exact legacy maximum while outbound profile rejects it") {
        val catalog = EmotionCatalog.of(
            List(EmotionCatalog.MAX_SIZE) { index ->
                EmotionId.of("emotify:${"x".repeat(53)}${index.toString().padStart(3, '0')}")
            },
        )
        val payload = ServerHelloPayload(
            ServerHello(
                ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(-1L)),
                ServerHello.MAX_COOLDOWN_MILLIS,
                catalog,
            ),
        )
        val encoded = ProtocolV1Codecs.serverHello.encodeToByteArray(payload.envelope)
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(encoded))
        val outbound = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.readableBytes() shouldBe ServerHelloPayload.MAX_BODY_BYTES
            ServerHelloPayload.STREAM_CODEC.decode(buffer) shouldBe payload
            shouldThrow<EncoderException> {
                ServerHelloPayload.STREAM_CODEC.encode(outbound, payload)
            }
            outbound.writerIndex() shouldBe 0
        } finally {
            buffer.release()
            outbound.release()
        }
    }

    test("server hello outbound profile accepts four thousand ninety six and rolls back four thousand ninety seven") {
        val acceptedPayload = serverPayloadWithEncodedSize(4_096, capabilities)
        val rejectedPayload = serverPayloadWithEncodedSize(4_097, capabilities)
        val acceptedBuffer = FriendlyByteBuf(Unpooled.buffer())
        val rejectedBuffer = FriendlyByteBuf(Unpooled.buffer())
        val acceptedDecodeBuffer = FriendlyByteBuf(
            Unpooled.wrappedBuffer(ProtocolV1Codecs.serverHello.encodeToByteArray(acceptedPayload.envelope)),
        )
        val rejectedDecodeBuffer = FriendlyByteBuf(
            Unpooled.wrappedBuffer(ProtocolV1Codecs.serverHello.encodeToByteArray(rejectedPayload.envelope)),
        )
        try {
            acceptedBuffer.writeBytes(byteArrayOf(0x45, 0x4D))
            rejectedBuffer.writeBytes(byteArrayOf(0x45, 0x4D))

            ServerHelloPayload.STREAM_CODEC.encode(acceptedBuffer, acceptedPayload)
            acceptedBuffer.writerIndex() shouldBe 4_098
            acceptedBuffer.getUnsignedByte(0).toInt() shouldBe 0x45
            acceptedBuffer.getUnsignedByte(1).toInt() shouldBe 0x4D

            shouldThrow<EncoderException> {
                ServerHelloPayload.STREAM_CODEC.encode(rejectedBuffer, rejectedPayload)
            }
            rejectedBuffer.writerIndex() shouldBe 2
            rejectedBuffer.getUnsignedByte(0).toInt() shouldBe 0x45
            rejectedBuffer.getUnsignedByte(1).toInt() shouldBe 0x4D
            ServerHelloPayload.STREAM_CODEC.decode(acceptedDecodeBuffer) shouldBe acceptedPayload
            ServerHelloPayload.STREAM_CODEC.decode(rejectedDecodeBuffer) shouldBe rejectedPayload
        } finally {
            acceptedBuffer.release()
            rejectedBuffer.release()
            acceptedDecodeBuffer.release()
            rejectedDecodeBuffer.release()
        }
    }

    test("server hello rejects more than five hundred and twelve catalog entries") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeByte(1)
            buffer.writeByte(0)
            buffer.writeByte(0)
            buffer.writeVarInt(1_200)
            buffer.writeVarInt(513)

            shouldThrow<DecoderException> {
                ServerHelloPayload.STREAM_CODEC.decode(buffer)
            }
        } finally {
            buffer.release()
        }
    }

    test("server hello preserves duplicate emotion IDs as a semantic failure") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeByte(1)
            buffer.writeByte(0)
            buffer.writeByte(0)
            buffer.writeVarInt(1_200)
            buffer.writeVarInt(2)
            repeat(2) {
                val id = EmotionId.of("emotify:happy").value.encodeToByteArray()
                buffer.writeVarInt(id.size)
                buffer.writeBytes(id)
            }

            ServerHelloPayload.STREAM_CODEC.decode(buffer).envelope shouldBe
                ServerHelloEnvelope.DuplicateEmotionIds
        } finally {
            buffer.release()
        }
    }

    test("hello codecs reject trailing bytes") {
        val serverBuffer = encoded(ServerHelloPayload.STREAM_CODEC, serverPayload)
        val clientBuffer = encoded(ClientHelloPayload.STREAM_CODEC, clientPayload)
        try {
            serverBuffer.writeByte(0)
            clientBuffer.writeByte(0)

            shouldThrow<DecoderException> {
                ServerHelloPayload.STREAM_CODEC.decode(serverBuffer)
            }
            shouldThrow<DecoderException> {
                ClientHelloPayload.STREAM_CODEC.decode(clientBuffer)
            }
        } finally {
            serverBuffer.release()
            clientBuffer.release()
        }
    }

    test("hello codecs reject non canonical feature flags") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeByte(1)
            buffer.writeByte(0)
            buffer.writeByte(0x80)
            buffer.writeByte(0)

            shouldThrow<DecoderException> {
                ClientHelloPayload.STREAM_CODEC.decode(buffer)
            }
        } finally {
            buffer.release()
        }
    }

    test("client hello accepts a canonical ten byte feature VarLong") {
        val payload = ClientHelloPayload(
            ClientHello(ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(-1L))),
        )

        roundTrip(ClientHelloPayload.STREAM_CODEC, payload) shouldBe payload
    }

    test("client hello rejects an eleven byte feature VarLong") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeByte(1)
            buffer.writeByte(0)
            repeat(10) { buffer.writeByte(0x80) }
            buffer.writeByte(0)

            shouldThrow<DecoderException> {
                ClientHelloPayload.STREAM_CODEC.decode(buffer)
            }.message shouldBe "Emotify payload exceeds 12 bytes"
        } finally {
            buffer.release()
        }
    }

    test("client hello accepts its twelve byte maximum and rejects a thirteenth byte") {
        val maximum = ClientHelloPayload(
            ClientHello(ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(-1L))),
        )
        val maximumBuffer = encoded(ClientHelloPayload.STREAM_CODEC, maximum)
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            maximumBuffer.readableBytes() shouldBe 12
            ClientHelloPayload.STREAM_CODEC.decode(maximumBuffer) shouldBe maximum
            buffer.writeZero(13)

            shouldThrow<DecoderException> {
                ClientHelloPayload.STREAM_CODEC.decode(buffer)
            }
        } finally {
            maximumBuffer.release()
            buffer.release()
        }
    }
}) {
    companion object {
        private fun serverPayloadWithEncodedSize(
            targetSize: Int,
            capabilities: ProtocolCapabilities,
        ): ServerHelloPayload {
            val fixedBodyBytes = 6
            val maximumIds = List(62) { index ->
                EmotionId.of("e:${index.toString().padStart(62, 'x')}")
            }
            val remainingEncodedIdBytes = targetSize - fixedBodyBytes - maximumIds.size * 65
            val finalIdLength = remainingEncodedIdBytes - 1
            val finalId = EmotionId.of("e:${"y".repeat(finalIdLength - 2)}")
            val catalog = EmotionCatalog.of(maximumIds + finalId)
            return ServerHelloPayload(ServerHello(capabilities, 250, catalog))
        }

        private fun <T : Any> roundTrip(
            codec: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T>,
            value: T,
        ): T {
            val buffer = encoded(codec, value)
            return try {
                codec.decode(buffer)
            } finally {
                buffer.release()
            }
        }

        private fun <T : Any> encoded(
            codec: net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T>,
            value: T,
        ): FriendlyByteBuf {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            codec.encode(buffer, value)
            return buffer
        }
    }
}
