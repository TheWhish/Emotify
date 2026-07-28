package me.whish.emotify.server

import me.whish.emotify.Emotify
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.protocol.EmotifyProtocol
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent

object ServerHandshakeLifecycle {
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val supportsHandshake = EmotifyChannels.supportsProtocol { type ->
            player.connection.hasChannel(type)
        }
        if (!supportsHandshake) {
            Emotify.LOGGER.info("Emotify handshake unavailable for player {}: optional channels are absent", player.uuid)
            return
        }

        val connectionId = ServerHandshakeService.nextConnectionId()
        player.connection.connection.channel().attr(ConnectionAttributes.serverConnectionId).set(connectionId)
        ServerHandshakeService.open(player.uuid, connectionId)
        player.connection.send(ServerHelloPayload(EmotifyProtocol.serverHello))
        Emotify.LOGGER.info(
            "Emotify handshake pending for player {} on connection {}",
            player.uuid,
            connectionId,
        )
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val connectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get() ?: return
        ServerHandshakeService.close(player.uuid, connectionId)
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        ServerHandshakeService.clear()
    }
}
