package me.whish.emotify.fabric.network

import io.netty.util.AttributeKey
import me.whish.emotify.fabric.mixin.ConnectionAccessor
import me.whish.emotify.fabric.mixin.ServerCommonPacketListenerAccessor
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetChunkPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionPlayPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl

object FabricChannelSupport {
    private val configurationChannels = AttributeKey.valueOf<Int>(
        "emotify:fabric_configuration_clientbound_channels",
    )

    fun registerConfigurationChannels(
        handler: ServerConfigurationPacketListenerImpl,
        channels: Collection<ResourceLocation>,
    ): Boolean = updateConfigurationChannels(handler) { current ->
            FabricClientboundChannelSet.register(current, channels)
        }

    fun unregisterConfigurationChannels(
        handler: ServerConfigurationPacketListenerImpl,
        channels: Collection<ResourceLocation>,
    ): Boolean = updateConfigurationChannels(handler) { current ->
            FabricClientboundChannelSet.unregister(current, channels)
        }

    fun clientCanReceiveServerPayloads(player: ServerPlayer): Boolean =
        FabricClientboundChannelSet.supportsProtocol(
            ServerPlayNetworking.getSendable(player),
            configurationMask(player.connection as ServerCommonPacketListenerAccessor),
        )

    fun requiresDeferredOpen(player: ServerPlayer): Boolean {
        val playChannels = ServerPlayNetworking.getSendable(player)
        val configurationMask = configurationMask(player.connection as ServerCommonPacketListenerAccessor)
        return FabricClientboundChannelSet.supportsProtocol(playChannels, configurationMask) &&
            !FabricClientboundChannelSet.supportsProtocol(playChannels, FabricClientboundChannelSet.EMPTY)
    }

    fun clientCanReceiveCustomEmojis(player: ServerPlayer): Boolean {
        val playChannels = ServerPlayNetworking.getSendable(player)
        val configurationMask = configurationMask(player.connection as ServerCommonPacketListenerAccessor)
        return FabricClientboundChannelSet.supportsCustomEmojis(playChannels, configurationMask)
    }

    fun clientCanReceiveLosslessCustomEmojis(player: ServerPlayer): Boolean {
        val playChannels = ServerPlayNetworking.getSendable(player)
        val configurationMask = configurationMask(player.connection as ServerCommonPacketListenerAccessor)
        return FabricClientboundChannelSet.supportsLosslessCustomEmojis(playChannels, configurationMask)
    }

    fun belongsToConfigurationConnection(
        handler: ServerConfigurationPacketListenerImpl,
        player: ServerPlayer,
    ): Boolean = connection(handler) === connection(player.connection as ServerCommonPacketListenerAccessor)

    private fun updateConfigurationChannels(
        handler: ServerConfigurationPacketListenerImpl,
        update: (Int) -> Int,
    ): Boolean {
        val connection = connection(handler)
        val attribute = (connection as ConnectionAccessor).emotifyChannel.attr(configurationChannels)
        val current = attribute.get() ?: FabricClientboundChannelSet.EMPTY
        val updated = update(current)
        if (updated == current) {
            return false
        }
        attribute.set(updated)
        return true
    }

    private fun connection(handler: ServerConfigurationPacketListenerImpl): Connection =
        connection(handler as ServerCommonPacketListenerAccessor)

    private fun connection(accessor: ServerCommonPacketListenerAccessor): Connection = accessor.emotifyConnection

    private fun configurationMask(accessor: ServerCommonPacketListenerAccessor): Int =
        configurationMask(connection(accessor))

    private fun configurationMask(connection: Connection): Int =
        (connection as ConnectionAccessor).emotifyChannel.attr(configurationChannels).get()
            ?: FabricClientboundChannelSet.EMPTY
}

internal object FabricClientboundChannelSet {
    const val EMPTY = 0

    fun register(current: Int, channels: Collection<ResourceLocation>): Int {
        var updated = current
        for (channel in channels) {
            updated = updated or flag(channel)
        }
        return updated
    }

    fun unregister(current: Int, channels: Collection<ResourceLocation>): Int {
        var removed = EMPTY
        for (channel in channels) {
            removed = removed or flag(channel)
        }
        return current and removed.inv()
    }

    fun supportsProtocol(playChannels: Set<ResourceLocation>, configurationMask: Int): Boolean =
        supports(FabricServerHelloPayload.TYPE.id(), playChannels, configurationMask) &&
            supports(FabricEmotionPlayPayload.TYPE.id(), playChannels, configurationMask) &&
            supports(FabricSelectionRejectedPayload.TYPE.id(), playChannels, configurationMask)

    fun supportsCustomEmojis(playChannels: Set<ResourceLocation>, configurationMask: Int): Boolean =
        supports(FabricCustomEmojiAssetPayload.TYPE.id(), playChannels, configurationMask) &&
            supports(FabricCustomEmotionPlayPayload.TYPE.id(), playChannels, configurationMask)

    fun supportsLosslessCustomEmojis(playChannels: Set<ResourceLocation>, configurationMask: Int): Boolean =
        supportsCustomEmojis(playChannels, configurationMask) &&
            supports(FabricCustomEmojiAssetChunkPayload.TYPE.id(), playChannels, configurationMask)

    fun supports(
        channel: ResourceLocation,
        playChannels: Set<ResourceLocation>,
        configurationMask: Int,
    ): Boolean {
        val requiredFlag = flag(channel)
        return requiredFlag != EMPTY &&
            (channel in playChannels || configurationMask and requiredFlag != EMPTY)
    }

    private fun flag(channel: ResourceLocation): Int = when (channel) {
        FabricServerHelloPayload.TYPE.id() -> SERVER_HELLO
        FabricEmotionPlayPayload.TYPE.id() -> EMOTION_PLAY
        FabricSelectionRejectedPayload.TYPE.id() -> SELECTION_REJECTED
        FabricCustomEmojiAssetPayload.TYPE.id() -> CUSTOM_ASSET
        FabricCustomEmotionPlayPayload.TYPE.id() -> CUSTOM_PLAY
        FabricCustomEmojiAssetChunkPayload.TYPE.id() -> CUSTOM_ASSET_CHUNK
        else -> EMPTY
    }

    private const val SERVER_HELLO = 1
    private const val EMOTION_PLAY = 1 shl 1
    private const val SELECTION_REJECTED = 1 shl 2
    private const val CUSTOM_ASSET = 1 shl 3
    private const val CUSTOM_PLAY = 1 shl 4
    private const val CUSTOM_ASSET_CHUNK = 1 shl 5
}
