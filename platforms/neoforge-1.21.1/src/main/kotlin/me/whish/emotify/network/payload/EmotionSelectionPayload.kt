package me.whish.emotify.network.payload

import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class EmotionSelectionPayload(
    val selection: EmotionSelection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmotionSelectionPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<EmotionSelectionPayload>(
            ResourceLocation.parse(ProtocolV1Channels.SELECT),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmotionSelectionPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.selection,
            EmotionSelectionPayload::selection,
            ::EmotionSelectionPayload,
        )
    }
}
