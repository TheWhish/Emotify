package me.whish.emotify.network.payload

import io.netty.handler.codec.DecoderException
import me.whish.emotify.Emotify
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class EmotionPlayPayload(
    val play: EmotionPlay,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        private const val MAX_BODY_BYTES = 95

        val TYPE = CustomPacketPayload.Type<EmotionPlayPayload>(
            ResourceLocation.fromNamespaceAndPath(Emotify.ID, "play"),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmotionPlayPayload> =
            object : BoundedPayloadCodec<EmotionPlayPayload>(MAX_BODY_BYTES) {
                override fun encodeBody(buffer: FriendlyByteBuf, value: EmotionPlayPayload) {
                    buffer.writeVarInt(value.play.entityId.value)
                    buffer.writeUUID(value.play.sourceUuid)
                    buffer.writeVarLong(value.play.sequence.value)
                    buffer.writeEmotionId(value.play.emotionId)
                }

                override fun decodeBody(buffer: FriendlyByteBuf): EmotionPlayPayload {
                    val entityId = RuntimeEntityId.parse(buffer.readCanonicalVarInt())
                        ?: throw DecoderException("Runtime entity ID must be positive")
                    val sourceUuid = buffer.readUUID()
                    val sequence = EventSequence.parse(buffer.readCanonicalVarLong())
                        ?: throw DecoderException("Event sequence must be positive")
                    return EmotionPlayPayload(
                        EmotionPlay(entityId, sourceUuid, sequence, buffer.readEmotionId()),
                    )
                }
            }
    }
}
