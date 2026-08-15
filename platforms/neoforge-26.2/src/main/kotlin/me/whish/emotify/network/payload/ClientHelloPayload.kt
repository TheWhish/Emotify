package me.whish.emotify.network.payload

import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class ClientHelloPayload(
    val hello: ClientHello,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClientHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientHelloPayload>(
            Identifier.parse(ProtocolV1Channels.CLIENT_HELLO),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ClientHelloPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.clientHello,
            ClientHelloPayload::hello,
            ::ClientHelloPayload,
        )
    }
}
