package me.whish.emotify.fabric.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import java.security.MessageDigest
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.fabric.network.payload.FabricClientHelloPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionSelectionPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.runtime.FabricProtocol
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1MaliciousCorpus
import me.whish.emotify.wire.v1.ProtocolV1PayloadKind
import me.whish.emotify.wire.v1.WireCodec
import me.whish.emotify.wire.v1.WireDecodeException
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

@Suppress("unused")
class ProtocolV1FabricParityTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)

    test("Fabric adapters match every frozen valid wire fixture") {
        assertParity(
            ProtocolV1Codecs.clientHello,
            FabricClientHelloPayload.STREAM_CODEC,
            FabricProtocol.clientHello,
            ::FabricClientHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.serverHello,
            FabricServerHelloPayload.STREAM_CODEC,
            ServerHelloEnvelope.Valid(ServerHello(capabilities, 250, EmotionCatalog.of(emptyList()))),
            ::FabricServerHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.serverHello,
            FabricServerHelloPayload.STREAM_CODEC,
            ServerHelloEnvelope.Valid(FabricProtocol.serverHello),
            ::FabricServerHelloPayload,
        )
        assertParity(
            ProtocolV1Codecs.selection,
            FabricEmotionSelectionPayload.STREAM_CODEC,
            EmotionSelection(EmotionId.of("a:b")),
            ::FabricEmotionSelectionPayload,
        )
        assertParity(
            ProtocolV1Codecs.play,
            FabricEmotionPlayPayload.STREAM_CODEC,
            EmotionPlay(
                RuntimeEntityId.of(300),
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                EventSequence.of(300),
                EmotionId.of("a:b"),
            ),
            ::FabricEmotionPlayPayload,
        )
        SelectionRejectionReason.entries.forEach { reason ->
            val rejection = SelectionRejected(SelectionRejectionCode.from(reason), 1_200)
            assertParity(
                ProtocolV1Codecs.selectionRejected,
                FabricSelectionRejectedPayload.STREAM_CODEC,
                rejection,
                ::FabricSelectionRejectedPayload,
            )
        }
        assertParity(
            ProtocolV1Codecs.selectionRejected,
            FabricSelectionRejectedPayload.STREAM_CODEC,
            SelectionRejected(SelectionRejectionCode(255), 0),
            ::FabricSelectionRejectedPayload,
        )
    }

    test("Fabric built in server hello matches the cross-platform golden payload") {
        val encoded = ProtocolV1Codecs.serverHello.encodeToByteArray(
            ServerHelloEnvelope.Valid(FabricProtocol.serverHello),
        )

        encoded.size shouldBe 2_929
        encoded.sha256() shouldBe "3469DB560E9BE655FA2F239B0FB219EFB45CBF6C78DCFB8813B93E42FEA1AB8E"
    }

    ProtocolV1MaliciousCorpus.inputs.forEach { input ->
        test("Fabric adapter matches pure rejection for ${input.name}") {
            val pureFailure = shouldThrow<WireDecodeException> {
                decodePure(input.payloadKind, input.bytes)
            }
            val adapterFailure = shouldThrow<DecoderException> {
                decodeAdapter(input.payloadKind, input.bytes)
            }
            adapterFailure.wireFailure().violation shouldBe pureFailure.violation
        }
    }

    test("Fabric adapter preserves duplicate catalog semantic results") {
        listOf(
            ProtocolV1MaliciousCorpus.duplicateCatalog,
            ProtocolV1MaliciousCorpus.invalidCooldownWithDuplicateCatalog,
        ).forEach { bytes ->
            val pure = ProtocolV1Codecs.serverHello.decode(bytes)
            val adapter = decodeAdapter(ProtocolV1PayloadKind.SERVER_HELLO, bytes) as FabricServerHelloPayload

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
            ProtocolV1PayloadKind.CLIENT_HELLO -> FabricClientHelloPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SERVER_HELLO -> FabricServerHelloPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SELECTION -> FabricEmotionSelectionPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.PLAY -> FabricEmotionPlayPayload.STREAM_CODEC.decode(buffer)
            ProtocolV1PayloadKind.SELECTION_REJECTED -> FabricSelectionRejectedPayload.STREAM_CODEC.decode(buffer)
        }
    } finally {
        buffer.release()
    }
}

private fun DecoderException.wireFailure(): WireDecodeException =
    generateSequence(this as Throwable?) { throwable -> throwable.cause }
        .filterIsInstance<WireDecodeException>()
        .first()

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
