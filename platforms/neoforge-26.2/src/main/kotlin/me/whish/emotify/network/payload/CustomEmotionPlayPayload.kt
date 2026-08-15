package me.whish.emotify.network.payload

import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class CustomEmotionPlayPayload(
    val play: CustomEmotionPlay,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CustomEmotionPlayPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CustomEmotionPlayPayload>(
            Identifier.parse(ProtocolV1Channels.CUSTOM_PLAY),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CustomEmotionPlayPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customPlay,
            CustomEmotionPlayPayload::play,
            ::CustomEmotionPlayPayload,
        )
    }
}
