package me.whish.emotify.network

import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

object EmotifyChannels {
    private val required = java.util.List.copyOf(
        listOf(
            ServerHelloPayload.TYPE,
            ClientHelloPayload.TYPE,
            EmotionSelectionPayload.TYPE,
            EmotionPlayPayload.TYPE,
            SelectionRejectedPayload.TYPE,
        ),
    )

    fun supportsProtocol(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        required.all(hasChannel)
}
