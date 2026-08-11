package me.whish.emotify.server

import me.whish.emotify.Emotify
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.server.core.OutboundDeliveryStatus
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

object ServerHandshakeLifecycle {
    private val serverHelloRetries = ServerHelloRetryQueue()

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerStarting)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onPlayerClone)
        NeoForge.EVENT_BUS.addListener(::onPlayerChangedDimension)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        ServerHandshakeService.initialize(event.server)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val supportsHandshake = EmotifyChannels.clientCanReceiveServerPayloads { type ->
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
        val server = player.server ?: return
        val channel = player.connection.connection.channel()
        channel.attr(ConnectionAttributes.serverConnectionId).set(connectionId)
        channel.attr(ConnectionAttributes.serverWorldEpoch).set(ConnectionWorldEpoch())
        val status = ServerHandshakeService.open(server, player.uuid, connectionId)
        if (status != OutboundDeliveryStatus.SENT) {
            serverHelloRetries.schedule(player.uuid, connectionId, MAX_SERVER_HELLO_ATTEMPTS - 1)
        }
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val connectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get() ?: return
        val server = player.server ?: return
        serverHelloRetries.remove(player.uuid, connectionId)
        ServerHandshakeService.close(server, player.uuid, connectionId)
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

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        ServerHandshakeService.drainCustomAssetVerifications(server)
        serverHelloRetries.drain { playerId, connectionId, attemptsRemaining ->
            val player = server.playerList.getPlayer(playerId) ?: return@drain
            val activeConnectionId = player.connection.connection
                .channel()
                .attr(ConnectionAttributes.serverConnectionId)
                .get()
            if (activeConnectionId != connectionId) {
                return@drain
            }
            if (!EmotifyChannels.clientCanReceiveServerPayloads(player.connection::hasChannel)) {
                return@drain
            }
            val status = ServerHandshakeService.refreshServerHello(server, playerId, connectionId)
            if (status != OutboundDeliveryStatus.SENT && attemptsRemaining > 1) {
                serverHelloRetries.schedule(playerId, connectionId, attemptsRemaining - 1)
            } else if (status != OutboundDeliveryStatus.SENT) {
                ServerHandshakeService.reportServerHelloRetryExhausted(playerId, connectionId, status)
            }
        }
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        serverHelloRetries.clear()
        ServerHandshakeService.clear(event.server)
    }

    private const val MAX_SERVER_HELLO_ATTEMPTS = 3
}
