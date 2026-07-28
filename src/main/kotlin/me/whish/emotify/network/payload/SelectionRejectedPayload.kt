package me.whish.emotify.network.payload

import me.whish.emotify.Emotify
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class SelectionRejectedPayload(
    val rejection: SelectionRejected,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SelectionRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SelectionRejectedPayload>(
            ResourceLocation.fromNamespaceAndPath(Emotify.ID, "selection_rejected"),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SelectionRejectedPayload> =
            object : BoundedPayloadCodec<SelectionRejectedPayload>(MAX_BODY_BYTES) {
                override fun encodeBody(buffer: FriendlyByteBuf, value: SelectionRejectedPayload) {
                    buffer.writeByte(value.rejection.code.value)
                    buffer.writeVarInt(value.rejection.retryAfterMillis)
                }

                override fun decodeBody(buffer: FriendlyByteBuf): SelectionRejectedPayload = SelectionRejectedPayload(
                    SelectionRejected(
                        SelectionRejectionCode(buffer.readUnsignedByte().toInt()),
                        buffer.readCanonicalVarInt(),
                    ),
                )
            }

        private const val MAX_BODY_BYTES = 3
    }
}
