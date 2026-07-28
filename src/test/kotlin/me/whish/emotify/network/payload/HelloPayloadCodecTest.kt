package me.whish.emotify.network.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import net.minecraft.network.FriendlyByteBuf

class HelloPayloadCodecTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val serverPayload = ServerHelloPayload(
        ServerHello(capabilities, 1_200, EmotionCatalog.BUILT_IN),
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

    test("server hello round trips a three hundred entry catalog") {
        val catalog = EmotionCatalog.of(
            List(300) { index -> EmotionId.of("emotify:emotion_$index") },
        )
        val payload = ServerHelloPayload(ServerHello(capabilities, 1_200, catalog))

        roundTrip(ServerHelloPayload.STREAM_CODEC, payload) shouldBe payload
    }

    test("server hello accepts its exact bounded maximum") {
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
        val buffer = encoded(ServerHelloPayload.STREAM_CODEC, payload)
        try {
            buffer.readableBytes() shouldBe ServerHelloPayload.MAX_BODY_BYTES
            ServerHelloPayload.STREAM_CODEC.decode(buffer) shouldBe payload
        } finally {
            buffer.release()
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
