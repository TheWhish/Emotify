package me.whish.emotify.network.payload

import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1Limits
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class ServerHelloPayload(
    val envelope: ServerHelloEnvelope,
) : CustomPacketPayload {
    constructor(hello: ServerHello) : this(ServerHelloEnvelope.Valid(hello))

    override fun type(): CustomPacketPayload.Type<ServerHelloPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerHelloPayload>(
            Identifier.parse(ProtocolV1Channels.SERVER_HELLO),
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ServerHelloPayload> = ProtocolV1PayloadCodec(
            ProtocolV1Codecs.serverHello,
            ServerHelloPayload::envelope,
            ::ServerHelloPayload,
            ProtocolV1Limits.PORTABLE_SERVER_HELLO_BODY_BYTES,
        )
    }
}
