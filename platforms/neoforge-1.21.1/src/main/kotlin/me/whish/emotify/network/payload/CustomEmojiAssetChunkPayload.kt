package me.whish.emotify.network.payload

import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class CustomEmojiAssetChunkPayload(
    val chunk: CustomEmojiAssetChunk,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CustomEmojiAssetChunkPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CustomEmojiAssetChunkPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_ASSET_CHUNK),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CustomEmojiAssetChunkPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customAssetChunk,
            CustomEmojiAssetChunkPayload::chunk,
            ::CustomEmojiAssetChunkPayload,
        )
    }
}
