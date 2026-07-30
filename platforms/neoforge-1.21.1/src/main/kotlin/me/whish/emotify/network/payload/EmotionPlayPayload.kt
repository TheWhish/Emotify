package me.whish.emotify.network.payload

import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class EmotionPlayPayload(
    val play: EmotionPlay,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<EmotionPlayPayload>(
            ResourceLocation.parse(ProtocolV1Channels.PLAY),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmotionPlayPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.play,
            EmotionPlayPayload::play,
            ::EmotionPlayPayload,
        )
    }
}
