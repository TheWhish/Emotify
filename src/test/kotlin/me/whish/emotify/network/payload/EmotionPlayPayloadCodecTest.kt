package me.whish.emotify.network.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import java.util.UUID
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import net.minecraft.network.FriendlyByteBuf

class EmotionPlayPayloadCodecTest : FunSpec({
    val play = EmotionPlay(
        RuntimeEntityId.of(300),
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        EventSequence.of(300),
        EmotionId.of("a:b"),
    )

    test("play round trips and keeps stable golden bytes") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            val payload = EmotionPlayPayload(play)
            EmotionPlayPayload.STREAM_CODEC.encode(buffer, payload)
            val encoded = ByteArray(buffer.readableBytes())
            buffer.getBytes(buffer.readerIndex(), encoded)

            encoded.toList() shouldContainExactly byteArrayOf(
                -84, 2,
                0, 17, 34, 51, 68, 85, 102, 119,
                -120, -103, -86, -69, -52, -35, -18, -1,
                -84, 2,
                3, 97, 58, 98,
            ).toList()
            EmotionPlayPayload.STREAM_CODEC.decode(buffer) shouldBe payload
        } finally {
            buffer.release()
        }
    }

    test("play admits the largest valid body of ninety five bytes") {
        val payload = EmotionPlayPayload(
            EmotionPlay(
                RuntimeEntityId.of(Int.MAX_VALUE),
                UUID(0L, 0L),
                EventSequence.of(Long.MAX_VALUE),
                EmotionId.of("a:${"b".repeat(62)}"),
            ),
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            EmotionPlayPayload.STREAM_CODEC.encode(buffer, payload)

            buffer.readableBytes() shouldBe 95
            EmotionPlayPayload.STREAM_CODEC.decode(buffer) shouldBe payload
        } finally {
            buffer.release()
        }
    }

    test("play rejects zero identity zero sequence and non canonical values") {
        val zeroEntity = playBytes(0, 1)
        val zeroSequence = playBytes(1, 0)
        val nonCanonicalEntity = FriendlyByteBuf(Unpooled.buffer())
        try {
            nonCanonicalEntity.writeBytes(byteArrayOf(-127, 0))
            nonCanonicalEntity.writeUUID(UUID(0L, 0L))
            nonCanonicalEntity.writeVarLong(1)
            nonCanonicalEntity.writeUtf("a:b")

            listOf(zeroEntity, zeroSequence, nonCanonicalEntity).forEach { buffer ->
                shouldThrow<DecoderException> {
                    EmotionPlayPayload.STREAM_CODEC.decode(buffer)
                }
            }
        } finally {
            zeroEntity.release()
            zeroSequence.release()
            nonCanonicalEntity.release()
        }
    }

    test("play rejects a trailing ninety five byte body and bounds ninety six bytes") {
        listOf(95, 96).forEach { targetSize ->
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                EmotionPlayPayload.STREAM_CODEC.encode(buffer, EmotionPlayPayload(play))
                buffer.writeZero(targetSize - buffer.readableBytes())

                val failure = shouldThrow<DecoderException> {
                    EmotionPlayPayload.STREAM_CODEC.decode(buffer)
                }
                if (targetSize == 96) {
                    failure.message shouldBe "Emotify payload exceeds 95 bytes"
                }
            } finally {
                buffer.release()
            }
        }
    }
}) {
    companion object {
        private fun playBytes(entityId: Int, sequence: Long): FriendlyByteBuf =
            FriendlyByteBuf(Unpooled.buffer()).apply {
                writeVarInt(entityId)
                writeUUID(UUID(0L, 0L))
                writeVarLong(sequence)
                writeUtf("a:b")
            }
    }
}
