package me.whish.emotify.wire.v1

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmotionSelection
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
    val customSelection: WireCodec<CustomEmotionSelection> = CustomEmotionSelectionCodec
    val customAsset: WireCodec<CustomEmojiTransfer> = CustomEmojiTransferCodec
    val customAssetChunk: WireCodec<CustomEmojiAssetChunk> = CustomEmojiAssetChunkCodec
    val customPlay: WireCodec<CustomEmotionPlay> = CustomEmotionPlayCodec
}

private object CustomEmojiAssetChunkCodec : BoundedWireCodec<CustomEmojiAssetChunk>(
    ProtocolV1Limits.CUSTOM_ASSET_CHUNK_BODY_BYTES,
) {
    override fun computeEncodedSize(value: CustomEmojiAssetChunk): Int {
        requireEncodableChunk(value)
        return me.whish.emotify.domain.CustomEmojiId.BYTE_LENGTH +
            varIntSize(value.totalBytes) +
            varIntSize(value.index) +
            varIntSize(value.count) +
            value.dataLength
    }

    override fun encodeBody(writer: WireWriter, value: CustomEmojiAssetChunk) {
        writer.writeCustomEmojiId(value.customEmojiId)
        writer.writeVarInt(value.totalBytes)
        writer.writeVarInt(value.index)
        writer.writeVarInt(value.count)
        value.writeData(writer)
    }

    override fun decodeBody(reader: WireReader): CustomEmojiAssetChunk {
        val id = reader.readCustomEmojiId()
        val totalBytes = reader.readCanonicalVarInt()
        val index = reader.readCanonicalVarInt()
        val count = reader.readCanonicalVarInt()
        val data = reader.readBytes(reader.remainingBytes)
        return try {
            CustomEmojiAssetChunk.takeOwnership(id, totalBytes, index, count, data)
                .also(::validateCustomEmojiAssetChunk)
        } catch (exception: IllegalArgumentException) {
            throw WireDecodeException(
                WireDecodeViolation.INVALID_CUSTOM_EMOJI,
                "Invalid custom emoji asset chunk",
                exception,
            )
        }
    }

    private fun requireEncodableChunk(chunk: CustomEmojiAssetChunk) {
        val message = customEmojiAssetChunkValidationError(chunk) ?: return
        throw WireEncodeException(WireEncodeViolation.UNENCODABLE_VALUE, message)
    }
}

private object CustomEmotionSelectionCodec : BoundedWireCodec<CustomEmotionSelection>(
    ProtocolV1Limits.CUSTOM_SELECT_BODY_BYTES,
) {
    override fun computeEncodedSize(value: CustomEmotionSelection): Int =
        me.whish.emotify.domain.CustomEmojiId.BYTE_LENGTH + customEmojiDescriptorSize(value.descriptor) + 1 +
            (value.asset?.let(::customEmojiAssetSize) ?: 0)

    override fun encodeBody(writer: WireWriter, value: CustomEmotionSelection) {
        writer.writeCustomEmojiId(value.customEmojiId)
        writer.writeCustomEmojiDescriptor(value.descriptor)
        writer.writeUnsignedByte(if (value.asset == null) 0 else 1)
        value.asset?.let(writer::writeCustomEmojiAsset)
    }

    override fun decodeBody(reader: WireReader): CustomEmotionSelection {
        val id = reader.readCustomEmojiId()
        val descriptor = reader.readCustomEmojiDescriptor()
        val asset = when (reader.readUnsignedByte()) {
            0 -> null
            1 -> reader.readCustomEmojiAsset(id)
            else -> throw WireDecodeException(
                WireDecodeViolation.INVALID_CUSTOM_EMOJI,
                "Invalid custom emoji asset presence flag",
            )
        }
        return CustomEmotionSelection(id, asset, descriptor)
    }
}

private object CustomEmojiTransferCodec : BoundedWireCodec<CustomEmojiTransfer>(
    ProtocolV1Limits.CUSTOM_ASSET_BODY_BYTES,
) {
    override fun computeEncodedSize(value: CustomEmojiTransfer): Int =
        me.whish.emotify.domain.CustomEmojiId.BYTE_LENGTH + customEmojiAssetSize(value.asset)

    override fun encodeBody(writer: WireWriter, value: CustomEmojiTransfer) {
        writer.writeCustomEmojiId(value.asset.id)
        writer.writeCustomEmojiAsset(value.asset)
    }

    override fun decodeBody(reader: WireReader): CustomEmojiTransfer {
        val id = reader.readCustomEmojiId()
        return CustomEmojiTransfer(reader.readCustomEmojiAsset(id))
    }
}

private object CustomEmotionPlayCodec : BoundedWireCodec<CustomEmotionPlay>(
    ProtocolV1Limits.CUSTOM_PLAY_BODY_BYTES,
) {
    override fun computeEncodedSize(value: CustomEmotionPlay): Int =
        varIntSize(value.entityId.value) + 16 + varLongSize(value.sequence.value) +
            me.whish.emotify.domain.CustomEmojiId.BYTE_LENGTH + customEmojiDescriptorSize(value.descriptor)

    override fun encodeBody(writer: WireWriter, value: CustomEmotionPlay) {
        writer.writeVarInt(value.entityId.value)
        writer.writeUuid(value.sourceUuid)
        writer.writeVarLong(value.sequence.value)
        writer.writeCustomEmojiId(value.customEmojiId)
        writer.writeCustomEmojiDescriptor(value.descriptor)
    }

    override fun decodeBody(reader: WireReader): CustomEmotionPlay {
        val entityId = RuntimeEntityId.parse(reader.readCanonicalVarInt())
            ?: throw WireDecodeException(WireDecodeViolation.INVALID_FIELD_VALUE, "Runtime entity ID must be positive")
        val sourceUuid = reader.readUuid()
        val sequence = EventSequence.parse(reader.readCanonicalVarLong())
            ?: throw WireDecodeException(WireDecodeViolation.INVALID_FIELD_VALUE, "Event sequence must be positive")
        val customEmojiId = reader.readCustomEmojiId()
        return CustomEmotionPlay(entityId, sourceUuid, sequence, customEmojiId, reader.readCustomEmojiDescriptor())
    }
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
