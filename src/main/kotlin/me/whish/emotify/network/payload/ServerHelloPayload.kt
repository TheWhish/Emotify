package me.whish.emotify.network.payload

import io.netty.handler.codec.EncoderException
import me.whish.emotify.Emotify
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class ServerHelloPayload(
    val envelope: ServerHelloEnvelope,
) : CustomPacketPayload {
    constructor(hello: ServerHello) : this(ServerHelloEnvelope.Valid(hello))

    override fun type(): CustomPacketPayload.Type<ServerHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerHelloPayload>(
            ResourceLocation.fromNamespaceAndPath(Emotify.ID, "server_hello"),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ServerHelloPayload> = object : BoundedPayloadCodec<ServerHelloPayload>(MAX_BODY_BYTES) {
            override fun encodeBody(buffer: FriendlyByteBuf, value: ServerHelloPayload) {
                val hello = when (val envelope = value.envelope) {
                    is ServerHelloEnvelope.Valid -> envelope.hello
                    ServerHelloEnvelope.DuplicateEmotionIds -> throw EncoderException(
                        "A semantically invalid server hello cannot be encoded",
                    )
                }
                buffer.writeCapabilities(hello.capabilities)
                buffer.writeVarInt(hello.cooldownMillis)
                buffer.writeVarInt(hello.emotionCatalog.ids.size)
                hello.emotionCatalog.ids.forEach { emotionId ->
                    buffer.writeEmotionId(emotionId)
                }
            }

            override fun decodeBody(buffer: FriendlyByteBuf): ServerHelloPayload {
                val capabilities = buffer.readCapabilities()
                val cooldownMillis = buffer.readCanonicalVarInt()
                val emotionIds = buffer.readCatalogIds()
                if (emotionIds.toSet().size != emotionIds.size) {
                    return ServerHelloPayload(ServerHelloEnvelope.DuplicateEmotionIds)
                }
                val emotionCatalog = EmotionCatalog.of(emotionIds)
                return ServerHelloPayload(ServerHelloEnvelope.Valid(ServerHello(capabilities, cooldownMillis, emotionCatalog)))
            }
        }

        internal const val MAX_BODY_BYTES = 33_296
    }
}
