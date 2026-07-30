package me.whish.emotify.network.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.runtime.EmotifyProtocol
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1MaliciousCorpus
import me.whish.emotify.wire.v1.ProtocolV1PayloadKind
import me.whish.emotify.wire.v1.WireCodec
import me.whish.emotify.wire.v1.WireDecodeException
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

@Suppress("unused")
class ProtocolV1NeoForgeParityTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)

    test("NeoForge adapters match every frozen valid wire fixture") {
        assertParity(
            ProtocolV1Codecs.clientHello,
            ClientHelloPayload.STREAM_CODEC,
            EmotifyProtocol.clientHello,
            ::ClientHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.serverHello,
            ServerHelloPayload.STREAM_CODEC,
            ServerHelloEnvelope.Valid(ServerHello(capabilities, 250, EmotionCatalog.of(emptyList()))),
            ::ServerHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.serverHello,
            ServerHelloPayload.STREAM_CODEC,
            ServerHelloEnvelope.Valid(EmotifyProtocol.serverHello),
            ::ServerHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.selection,
            EmotionSelectionPayload.STREAM_CODEC,
            EmotionSelection(EmotionId.of("a:b")),
            ::EmotionSelectionPayload,
        )
        assertParity(
            ProtocolV1Codecs.play,
            EmotionPlayPayload.STREAM_CODEC,
            EmotionPlay(
                RuntimeEntityId.of(300),
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                EventSequence.of(300),
                EmotionId.of("a:b"),
            ),
            ::EmotionPlayPayload,
        )
        SelectionRejectionReason.entries.forEach { reason ->
            val rejection = SelectionRejected(SelectionRejectionCode.from(reason), 1_200)
            assertParity(
                ProtocolV1Codecs.selectionRejected,
                SelectionRejectedPayload.STREAM_CODEC,
                rejection,
                ::SelectionRejectedPayload,
            )
        }
        assertParity(
            ProtocolV1Codecs.selectionRejected,
            SelectionRejectedPayload.STREAM_CODEC,
            SelectionRejected(SelectionRejectionCode(255), 0),
            ::SelectionRejectedPayload,
        )
    }

    ProtocolV1MaliciousCorpus.inputs.forEach { input ->
        test("NeoForge adapter matches pure rejection for ${input.name}") {
            val pureFailure = shouldThrow<WireDecodeException> {
                decodePure(input.payloadKind, input.bytes)
            }
            val adapterFailure = shouldThrow<DecoderException> {
                decodeAdapter(input.payloadKind, input.bytes)
            }

            adapterFailure.wireFailure().violation shouldBe pureFailure.violation
        }
    }

    test("NeoForge adapter matches duplicate catalog semantic results") {
        listOf(
            ProtocolV1MaliciousCorpus.duplicateCatalog,
            ProtocolV1MaliciousCorpus.invalidCooldownWithDuplicateCatalog,
        ).forEach { bytes ->
            val pure = ProtocolV1Codecs.serverHello.decode(bytes)
            val adapter = decodeAdapter(ProtocolV1PayloadKind.SERVER_HELLO, bytes) as ServerHelloPayload

            pure shouldBe ServerHelloEnvelope.DuplicateEmotionIds
            adapter.envelope shouldBe pure
        }
    }
})

private fun <TMessage : Any, TPayload : Any> assertParity(
    wireCodec: WireCodec<TMessage>,
    adapter: StreamCodec<FriendlyByteBuf, TPayload>,
    message: TMessage,
    wrap: (TMessage) -> TPayload,
) {
    val expectedBytes = wireCodec.encodeToByteArray(message)
    val encodedBuffer = FriendlyByteBuf(Unpooled.buffer())
    val decodeBuffer = FriendlyByteBuf(Unpooled.wrappedBuffer(expectedBytes))
    try {
        adapter.encode(encodedBuffer, wrap(message))
        val actualBytes = ByteArray(encodedBuffer.readableBytes())
        encodedBuffer.getBytes(encodedBuffer.readerIndex(), actualBytes)

        actualBytes.toList() shouldContainExactly expectedBytes.toList()
        adapter.decode(decodeBuffer) shouldBe wrap(message)
    } finally {
        encodedBuffer.release()
        decodeBuffer.release()
    }
}

private fun decodePure(payloadKind: ProtocolV1PayloadKind, bytes: ByteArray): Any = when (payloadKind) {
    ProtocolV1PayloadKind.CLIENT_HELLO -> ProtocolV1Codecs.clientHello.decode(bytes)
    ProtocolV1PayloadKind.SERVER_HELLO -> ProtocolV1Codecs.serverHello.decode(bytes)
    ProtocolV1PayloadKind.SELECTION -> ProtocolV1Codecs.selection.decode(bytes)
    ProtocolV1PayloadKind.PLAY -> ProtocolV1Codecs.play.decode(bytes)
    ProtocolV1PayloadKind.SELECTION_REJECTED -> ProtocolV1Codecs.selectionRejected.decode(bytes)
}

private fun decodeAdapter(payloadKind: ProtocolV1PayloadKind, bytes: ByteArray): Any {
    val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
    return try {
        when (payloadKind) {
            ProtocolV1PayloadKind.CLIENT_HELLO -> ClientHelloPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SERVER_HELLO -> ServerHelloPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SELECTION -> EmotionSelectionPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.PLAY -> EmotionPlayPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SELECTION_REJECTED -> SelectionRejectedPayload.STREAM_CODEC.decode(buffer)
        }
    } finally {
        buffer.release()
    }
}

private fun DecoderException.wireFailure(): WireDecodeException =
    generateSequence(this as Throwable?) { throwable -> throwable.cause }
        .filterIsInstance<WireDecodeException>()
        .first()
