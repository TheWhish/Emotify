package me.whish.emotify.network

import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

object EmotifyChannels {
    private val serverbound = java.util.List.copyOf(
        listOf(
            ClientHelloPayload.TYPE,
            EmotionSelectionPayload.TYPE,
        ),
    )
    private val clientbound = java.util.List.copyOf(
        listOf(
            ServerHelloPayload.TYPE,
            EmotionPlayPayload.TYPE,
            SelectionRejectedPayload.TYPE,
        ),
    )

    fun serverCanReceiveClientPayloads(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        serverbound.all(hasChannel)

    fun clientCanReceiveServerPayloads(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        clientbound.all(hasChannel)
}
