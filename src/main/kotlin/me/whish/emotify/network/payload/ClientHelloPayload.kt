package me.whish.emotify.network.payload

import me.whish.emotify.Emotify
import me.whish.emotify.protocol.ClientHello
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data class ClientHelloPayload(
    val hello: ClientHello,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClientHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientHelloPayload>(
            ResourceLocation.fromNamespaceAndPath(Emotify.ID, "client_hello"),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ClientHelloPayload> = object : BoundedPayloadCodec<ClientHelloPayload>(MAX_BODY_BYTES) {
            override fun encodeBody(buffer: FriendlyByteBuf, value: ClientHelloPayload) {
                buffer.writeCapabilities(value.hello.capabilities)
            }

            override fun decodeBody(buffer: FriendlyByteBuf): ClientHelloPayload =
                ClientHelloPayload(ClientHello(buffer.readCapabilities()))
        }

        private const val MAX_BODY_BYTES = 12
    }
}
