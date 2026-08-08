package me.whish.emotify.network.payload

import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

class CustomEmojiAssetPayload private constructor(
    val transfer: CustomEmojiTransfer,
    private val encodedBody: Lazy<ByteArray>?,
) : CustomPacketPayload {
    constructor(transfer: CustomEmojiTransfer) : this(transfer, null)

    override fun type(): CustomPacketPayload.Type<CustomEmojiAssetPayload> = TYPE

    internal fun preEncodedBody(): ByteArray? = encodedBody?.value

    override fun equals(other: Any?): Boolean =
        this === other || other is CustomEmojiAssetPayload && transfer == other.transfer

    override fun hashCode(): Int = transfer.hashCode()

    override fun toString(): String = "CustomEmojiAssetPayload(transfer=$transfer)"

    companion object {
        val TYPE = CustomPacketPayload.Type<CustomEmojiAssetPayload>(
            ResourceLocation.parse(ProtocolV1Channels.CUSTOM_ASSET),
        )
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CustomEmojiAssetPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.customAsset,
            CustomEmojiAssetPayload::transfer,
            ::CustomEmojiAssetPayload,
            preEncodedBody = CustomEmojiAssetPayload::preEncodedBody,
        )

        internal fun prepared(transfer: CustomEmojiTransfer): CustomEmojiAssetPayload =
            CustomEmojiAssetPayload(
                transfer,
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    ProtocolV1Codecs.customAsset.encodeToByteArray(transfer)
                },
            )
    }
}
