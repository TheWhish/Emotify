package me.whish.emotify.fabric.network.payload

import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1Limits
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class FabricClientHelloPayload(
    val hello: ClientHello,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricClientHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricClientHelloPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CLIENT_HELLO),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricClientHelloPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.clientHello,
            FabricClientHelloPayload::hello,
            ::FabricClientHelloPayload,
        )
    }
}

data class FabricServerHelloPayload(
    val envelope: ServerHelloEnvelope,
) : CustomPacketPayload {
    constructor(hello: ServerHello) : this(ServerHelloEnvelope.Valid(hello))

    override fun type(): CustomPacketPayload.Type<FabricServerHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricServerHelloPayload>(
            ResourceLocation.parse(ProtocolV1Channels.SERVER_HELLO),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricServerHelloPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.serverHello,
            FabricServerHelloPayload::envelope,
            ::FabricServerHelloPayload,
            ProtocolV1Limits.PORTABLE_SERVER_HELLO_BODY_BYTES,
        )
    }
}

data class FabricEmotionSelectionPayload(
    val selection: EmotionSelection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricEmotionSelectionPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricEmotionSelectionPayload>(
            ResourceLocation.parse(ProtocolV1Channels.SELECT),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricEmotionSelectionPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.selection,
            FabricEmotionSelectionPayload::selection,
            ::FabricEmotionSelectionPayload,
        )
    }
}

data class FabricEmotionPlayPayload(
    val play: EmotionPlay,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricEmotionPlayPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricEmotionPlayPayload>(
            ResourceLocation.parse(ProtocolV1Channels.PLAY),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricEmotionPlayPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.play,
            FabricEmotionPlayPayload::play,
            ::FabricEmotionPlayPayload,
        )
    }
}

data class FabricSelectionRejectedPayload(
    val rejection: SelectionRejected,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricSelectionRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricSelectionRejectedPayload>(
            ResourceLocation.parse(ProtocolV1Channels.SELECTION_REJECTED),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricSelectionRejectedPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.selectionRejected,
            FabricSelectionRejectedPayload::rejection,
            ::FabricSelectionRejectedPayload,
        )
    }
}

data class FabricCustomEmotionSelectionPayload(
    val selection: CustomEmotionSelection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricCustomEmotionSelectionPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricCustomEmotionSelectionPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_SELECT),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricCustomEmotionSelectionPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customSelection,
            FabricCustomEmotionSelectionPayload::selection,
            ::FabricCustomEmotionSelectionPayload,
        )
    }
}

class FabricCustomEmojiAssetPayload private constructor(
    val transfer: CustomEmojiTransfer,
    private val encodedBody: Lazy<ByteArray>?,
) : CustomPacketPayload {
    constructor(transfer: CustomEmojiTransfer) : this(transfer, null)

    override fun type(): CustomPacketPayload.Type<FabricCustomEmojiAssetPayload> = TYPE

    internal fun preEncodedBody(): ByteArray? = encodedBody?.value

    override fun equals(other: Any?): Boolean =
        this === other || other is FabricCustomEmojiAssetPayload && transfer == other.transfer

    override fun hashCode(): Int = transfer.hashCode()

    override fun toString(): String = "FabricCustomEmojiAssetPayload(transfer=$transfer)"

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricCustomEmojiAssetPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_ASSET),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricCustomEmojiAssetPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customAsset,
            FabricCustomEmojiAssetPayload::transfer,
            ::FabricCustomEmojiAssetPayload,
            preEncodedBody = FabricCustomEmojiAssetPayload::preEncodedBody,
        )

        internal fun prepared(transfer: CustomEmojiTransfer): FabricCustomEmojiAssetPayload =
            FabricCustomEmojiAssetPayload(
                transfer,
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    ProtocolV1Codecs.customAsset.encodeToByteArray(transfer)
                },
            )
    }
}

data class FabricCustomEmojiAssetChunkPayload(
    val chunk: CustomEmojiAssetChunk,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricCustomEmojiAssetChunkPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricCustomEmojiAssetChunkPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_ASSET_CHUNK),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricCustomEmojiAssetChunkPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customAssetChunk,
            FabricCustomEmojiAssetChunkPayload::chunk,
            ::FabricCustomEmojiAssetChunkPayload,
        )
    }
}

data class FabricCustomEmotionPlayPayload(
    val play: CustomEmotionPlay,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FabricCustomEmotionPlayPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FabricCustomEmotionPlayPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_PLAY),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, FabricCustomEmotionPlayPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customPlay,
            FabricCustomEmotionPlayPayload::play,
            ::FabricCustomEmotionPlayPayload,
        )
    }
}
