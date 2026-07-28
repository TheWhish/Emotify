package me.whish.emotify.network.payload

import me.whish.emotify.Emotify
import me.whish.emotify.protocol.EmotionSelection
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
            ResourceLocation.fromNamespaceAndPath(Emotify.ID, "select"),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmotionSelectionPayload> =
            object : BoundedPayloadCodec<EmotionSelectionPayload>(MAX_BODY_BYTES) {
                override fun encodeBody(buffer: FriendlyByteBuf, value: EmotionSelectionPayload) {
                    buffer.writeEmotionId(value.selection.emotionId)
                }

                override fun decodeBody(buffer: FriendlyByteBuf): EmotionSelectionPayload =
                    EmotionSelectionPayload(EmotionSelection(buffer.readEmotionId()))
            }

        private const val MAX_BODY_BYTES = 65
    }
}
