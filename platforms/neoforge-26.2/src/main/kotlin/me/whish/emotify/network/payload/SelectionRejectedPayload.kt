package me.whish.emotify.network.payload

import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class SelectionRejectedPayload(
    val rejection: SelectionRejected,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SelectionRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SelectionRejectedPayload>(
            Identifier.parse(ProtocolV1Channels.SELECTION_REJECTED),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SelectionRejectedPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.selectionRejected,
            SelectionRejectedPayload::rejection,
            ::SelectionRejectedPayload,
        )
    }
}
