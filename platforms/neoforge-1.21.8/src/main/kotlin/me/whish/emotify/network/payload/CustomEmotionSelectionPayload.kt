package me.whish.emotify.network.payload

import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class CustomEmotionSelectionPayload(
    val selection: CustomEmotionSelection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CustomEmotionSelectionPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CustomEmotionSelectionPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_SELECT),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CustomEmotionSelectionPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customSelection,
            CustomEmotionSelectionPayload::selection,
            ::CustomEmotionSelectionPayload,
        )
    }
}
