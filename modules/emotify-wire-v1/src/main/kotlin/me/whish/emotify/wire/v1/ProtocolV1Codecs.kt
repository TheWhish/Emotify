package me.whish.emotify.wire.v1

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope

object ProtocolV1Codecs {
    val clientHello: WireCodec<ClientHello> = ClientHelloCodec
    val serverHello: WireCodec<ServerHelloEnvelope> = ServerHelloCodec
    val selection: WireCodec<EmotionSelection> = EmotionSelectionCodec
    val play: WireCodec<EmotionPlay> = EmotionPlayCodec
    val selectionRejected: WireCodec<SelectionRejected> = SelectionRejectedCodec
}

object ProtocolV1PortableProfile {
    fun requireServerHello(hello: ServerHello): ServerHello {
        val encodedSize = ProtocolV1Codecs.serverHello.encodedSize(ServerHelloEnvelope.Valid(hello))
        if (encodedSize > ProtocolV1Limits.PORTABLE_SERVER_HELLO_BODY_BYTES) {
            throw WireEncodeException(
                WireEncodeViolation.BODY_TOO_LARGE,
                "Portable Protocol 1 server hello exceeds ${ProtocolV1Limits.PORTABLE_SERVER_HELLO_BODY_BYTES} bytes",
            )
        }
        return hello
    }
}

private object ClientHelloCodec : BoundedWireCodec<ClientHello>(ProtocolV1Limits.CLIENT_HELLO_BODY_BYTES) {
    override fun computeEncodedSize(value: ClientHello): Int = capabilitiesSize(value.capabilities)

    override fun encodeBody(writer: WireWriter, value: ClientHello) {
        writer.writeCapabilities(value.capabilities)
    }

    override fun decodeBody(reader: WireReader): ClientHello = ClientHello(reader.readCapabilities())
}

private object ServerHelloCodec : BoundedWireCodec<ServerHelloEnvelope>(ProtocolV1Limits.SERVER_HELLO_BODY_BYTES) {
    override fun computeEncodedSize(value: ServerHelloEnvelope): Int {
        val hello = value.encodableHello()
        return capabilitiesSize(hello.capabilities) +
            varIntSize(hello.cooldownMillis) +
            varIntSize(hello.emotionCatalog.ids.size) +
            hello.emotionCatalog.ids.sumOf(::emotionIdSize)
    }

    override fun encodeBody(writer: WireWriter, value: ServerHelloEnvelope) {
        val hello = value.encodableHello()
        writer.writeCapabilities(hello.capabilities)
        writer.writeVarInt(hello.cooldownMillis)
        writer.writeVarInt(hello.emotionCatalog.ids.size)
        hello.emotionCatalog.ids.forEach(writer::writeEmotionId)
    }

    override fun decodeBody(reader: WireReader): ServerHelloEnvelope {
        val capabilities = reader.readCapabilities()
        val cooldownMillis = reader.readCanonicalVarInt()
        val catalog = reader.readCatalog()
        if (catalog.containsDuplicates) {
            return ServerHelloEnvelope.DuplicateEmotionIds
        }
        val hello = ServerHello(capabilities, cooldownMillis, EmotionCatalog.of(catalog.ids))
        return ServerHelloEnvelope.Valid(hello)
    }

    private fun ServerHelloEnvelope.encodableHello(): ServerHello = when (this) {
        is ServerHelloEnvelope.Valid -> hello
        ServerHelloEnvelope.DuplicateEmotionIds -> throw WireEncodeException(
            WireEncodeViolation.UNENCODABLE_VALUE,
            "A semantically invalid Protocol 1 server hello cannot be encoded",
        )
    }
}

private object EmotionSelectionCodec : BoundedWireCodec<EmotionSelection>(ProtocolV1Limits.SELECT_BODY_BYTES) {
    override fun computeEncodedSize(value: EmotionSelection): Int = emotionIdSize(value.emotionId)

    override fun encodeBody(writer: WireWriter, value: EmotionSelection) {
        writer.writeEmotionId(value.emotionId)
    }

    override fun decodeBody(reader: WireReader): EmotionSelection = EmotionSelection(reader.readEmotionId())
}

private object EmotionPlayCodec : BoundedWireCodec<EmotionPlay>(ProtocolV1Limits.PLAY_BODY_BYTES) {
    override fun computeEncodedSize(value: EmotionPlay): Int =
        varIntSize(value.entityId.value) +
            UUID_BYTES +
            varLongSize(value.sequence.value) +
            emotionIdSize(value.emotionId)

    override fun encodeBody(writer: WireWriter, value: EmotionPlay) {
        writer.writeVarInt(value.entityId.value)
        writer.writeUuid(value.sourceUuid)
        writer.writeVarLong(value.sequence.value)
        writer.writeEmotionId(value.emotionId)
    }

    override fun decodeBody(reader: WireReader): EmotionPlay {
        val entityId = RuntimeEntityId.parse(reader.readCanonicalVarInt())
            ?: throw WireDecodeException(WireDecodeViolation.INVALID_FIELD_VALUE, "Runtime entity ID must be positive")
        val sourceUuid = reader.readUuid()
        val sequence = EventSequence.parse(reader.readCanonicalVarLong())
            ?: throw WireDecodeException(WireDecodeViolation.INVALID_FIELD_VALUE, "Event sequence must be positive")
        return EmotionPlay(entityId, sourceUuid, sequence, reader.readEmotionId())
    }

    private const val UUID_BYTES = 16
}

private object SelectionRejectedCodec : BoundedWireCodec<SelectionRejected>(
    ProtocolV1Limits.SELECTION_REJECTED_BODY_BYTES,
) {
    override fun computeEncodedSize(value: SelectionRejected): Int =
        1 + varIntSize(value.retryAfterMillis)

    override fun encodeBody(writer: WireWriter, value: SelectionRejected) {
        writer.writeUnsignedByte(value.code.value)
        writer.writeVarInt(value.retryAfterMillis)
    }

    override fun decodeBody(reader: WireReader): SelectionRejected = SelectionRejected(
        SelectionRejectionCode(reader.readUnsignedByte()),
        reader.readCanonicalVarInt(),
    )
}
