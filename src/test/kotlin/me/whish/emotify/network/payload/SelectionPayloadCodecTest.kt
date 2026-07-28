package me.whish.emotify.network.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

class SelectionPayloadCodecTest : FunSpec({
    test("selection round trips a sixty four byte emotion ID") {
        val emotionId = EmotionId.of("a:${"b".repeat(62)}")
        val payload = EmotionSelectionPayload(EmotionSelection(emotionId))

        roundTrip(EmotionSelectionPayload.STREAM_CODEC, payload) shouldBe payload
    }

    test("selection round trips a sixty three byte emotion ID") {
        val emotionId = EmotionId.of("a:${"b".repeat(61)}")
        val payload = EmotionSelectionPayload(EmotionSelection(emotionId))

        roundTrip(EmotionSelectionPayload.STREAM_CODEC, payload) shouldBe payload
    }

    test("rejection round trips every known reason") {
        SelectionRejectionReason.entries.forEach { reason ->
            val payload = SelectionRejectedPayload(
                SelectionRejected(SelectionRejectionCode.from(reason), 1_200),
            )

            roundTrip(SelectionRejectedPayload.STREAM_CODEC, payload) shouldBe payload
        }
    }

    test("rejection preserves an unknown reason code") {
        val payload = SelectionRejectedPayload(
            SelectionRejected(SelectionRejectionCode(255), 0),
        )

        roundTrip(SelectionRejectedPayload.STREAM_CODEC, payload) shouldBe payload
        payload.rejection.code.knownReason shouldBe null
    }

    test("selection rejects a sixty five byte emotion ID") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeVarInt(65)
            buffer.writeBytes("a:${"b".repeat(63)}".encodeToByteArray())

            shouldThrow<DecoderException> {
                EmotionSelectionPayload.STREAM_CODEC.decode(buffer)
            }.message shouldBe "Emotify payload exceeds 65 bytes"
        } finally {
            buffer.release()
        }
    }

    test("selection rejects negative and six byte lengths") {
        listOf(
            byteArrayOf(-1, -1, -1, -1, 15),
            byteArrayOf(-128, -128, -128, -128, -128, 0),
        ).forEach { encodedLength ->
            val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(encodedLength))
            try {
                shouldThrow<DecoderException> {
                    EmotionSelectionPayload.STREAM_CODEC.decode(buffer)
                }
            } finally {
                buffer.release()
            }
        }
    }

    test("selection rejects non canonical length and trailing bytes") {
        val nonCanonical = FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(-125, 0, 97, 58, 98)))
        val trailing = encoded(
            EmotionSelectionPayload.STREAM_CODEC,
            EmotionSelectionPayload(EmotionSelection(EmotionId.of("a:b"))),
        )
        try {
            trailing.writeByte(0)

            shouldThrow<DecoderException> {
                EmotionSelectionPayload.STREAM_CODEC.decode(nonCanonical)
            }
            shouldThrow<DecoderException> {
                EmotionSelectionPayload.STREAM_CODEC.decode(trailing)
            }
        } finally {
            nonCanonical.release()
            trailing.release()
        }
    }

    test("bounded codec admits body sizes ninety five and ninety six but rejects ninety seven") {
        val codec = object : BoundedPayloadCodec<Int>(96) {
            override fun encodeBody(buffer: FriendlyByteBuf, value: Int) {
                buffer.writeZero(value)
            }

            override fun decodeBody(buffer: FriendlyByteBuf): Int {
                val size = buffer.readableBytes()
                buffer.skipBytes(size)
                return size
            }
        }
        val belowLimit = FriendlyByteBuf(Unpooled.buffer().writeZero(95))
        val atLimit = FriendlyByteBuf(Unpooled.buffer().writeZero(96))
        val aboveLimit = FriendlyByteBuf(Unpooled.buffer().writeZero(97))
        try {
            codec.decode(belowLimit) shouldBe 95
            codec.decode(atLimit) shouldBe 96
            shouldThrow<DecoderException> {
                codec.decode(aboveLimit)
            }
        } finally {
            belowLimit.release()
            atLimit.release()
            aboveLimit.release()
        }
    }

    test("selection rejects a truncated ID") {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeVarInt(5)
            buffer.writeBytes(byteArrayOf(97, 58, 98))

            shouldThrow<DecoderException> {
                EmotionSelectionPayload.STREAM_CODEC.decode(buffer)
            }
        } finally {
            buffer.release()
        }
    }

    test("rejection codec rejects invalid retry encodings and trailing bytes") {
        val tooLarge = FriendlyByteBuf(Unpooled.buffer())
        val nonCanonical = FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(0, -128, 0)))
        val truncated = FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(0, -128)))
        val trailing = FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(0, 0, 0)))
        try {
            tooLarge.writeByte(0)
            tooLarge.writeVarInt(10_001)

            listOf(tooLarge, nonCanonical, truncated, trailing).forEach { buffer ->
                shouldThrow<DecoderException> {
                    SelectionRejectedPayload.STREAM_CODEC.decode(buffer)
                }
            }
        } finally {
            tooLarge.release()
            nonCanonical.release()
            truncated.release()
            trailing.release()
        }
    }

    test("rejection codec rejects bodies above three bytes") {
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(byteArrayOf(0, 0, 0, 0)))
        try {
            shouldThrow<DecoderException> {
                SelectionRejectedPayload.STREAM_CODEC.decode(buffer)
            }
        } finally {
            buffer.release()
        }
    }
}) {
    companion object {
        private fun <T : Any> roundTrip(codec: StreamCodec<FriendlyByteBuf, T>, value: T): T {
            val buffer = encoded(codec, value)
            return try {
                codec.decode(buffer)
            } finally {
                buffer.release()
            }
        }

        private fun <T : Any> encoded(codec: StreamCodec<FriendlyByteBuf, T>, value: T): FriendlyByteBuf {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            codec.encode(buffer, value)
            return buffer
        }
    }
}
