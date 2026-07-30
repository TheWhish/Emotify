package me.whish.emotify.fabric.network.payload

import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
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
        internal const val MAX_BODY_BYTES = ProtocolV1Limits.SERVER_HELLO_BODY_BYTES
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
