package me.whish.emotify.server

import me.whish.emotify.Emotify
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent

object ServerHandshakeLifecycle {
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onPlayerClone)
        NeoForge.EVENT_BUS.addListener(::onPlayerChangedDimension)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val supportsHandshake = EmotifyChannels.supportsProtocol { type ->
            player.connection.hasChannel(type)
        }
        if (!supportsHandshake) {
            Emotify.LOGGER.debug(
                "Emotify handshake unavailable for player {}: optional channels are absent",
                player.uuid,
            )
            return
        }

        val connectionId = ServerHandshakeService.nextConnectionId()
        val channel = player.connection.connection.channel()
        channel.attr(ConnectionAttributes.serverConnectionId).set(connectionId)
        channel.attr(ConnectionAttributes.serverWorldEpoch).set(ConnectionWorldEpoch())
        ServerHandshakeService.open(player.server, player.uuid, connectionId)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val connectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get() ?: return
        ServerHandshakeService.close(player.server, player.uuid, connectionId)
    }

    private fun onPlayerClone(event: PlayerEvent.Clone) {
        advanceWorldEpoch(event.entity as? ServerPlayer)
    }

    private fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        advanceWorldEpoch(event.entity as? ServerPlayer)
    }

    private fun advanceWorldEpoch(player: ServerPlayer?) {
        player?.connection?.connection
            ?.channel()
            ?.attr(ConnectionAttributes.serverWorldEpoch)
            ?.get()
            ?.advance()
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        ServerHandshakeService.clear(event.server)
    }
}
