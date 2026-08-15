package me.whish.emotify.network

import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.network.payload.CustomEmojiAssetPayload
import me.whish.emotify.network.payload.CustomEmojiAssetChunkPayload
import me.whish.emotify.network.payload.CustomEmotionPlayPayload
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

    fun clientCanReceiveCustomEmojis(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        hasChannel(CustomEmojiAssetPayload.TYPE) && hasChannel(CustomEmotionPlayPayload.TYPE)

    fun clientCanReceiveLosslessCustomEmojis(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        clientCanReceiveCustomEmojis(hasChannel) && hasChannel(CustomEmojiAssetChunkPayload.TYPE)

    fun serverCanReceiveLosslessCustomEmojis(hasChannel: (CustomPacketPayload.Type<*>) -> Boolean): Boolean =
        hasChannel(CustomEmojiAssetChunkPayload.TYPE)
}
