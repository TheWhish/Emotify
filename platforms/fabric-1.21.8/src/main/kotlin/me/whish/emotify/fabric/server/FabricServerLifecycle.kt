package me.whish.emotify.fabric.server

import java.util.UUID
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.config.FabricServerConfig
import me.whish.emotify.fabric.network.FabricChannelSupport
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetChunkPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionPlayPayload
import me.whish.emotify.server.core.OutboundDeliveryStatus
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.S2CConfigurationChannelEvents
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl

object FabricServerLifecycle {
    private val deferredConnectionOpens = FabricDeferredConnectionOpenQueue()
    private val outboundChannels = setOf(
        FabricServerHelloPayload.TYPE.id(),
        FabricEmotionPlayPayload.TYPE.id(),
        FabricSelectionRejectedPayload.TYPE.id(),
        FabricCustomEmojiAssetPayload.TYPE.id(),
        FabricCustomEmojiAssetChunkPayload.TYPE.id(),
        FabricCustomEmotionPlayPayload.TYPE.id(),
    )

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            FabricServerConfig.initialize()
            FabricServerRuntime.initialize(server)
        }
        S2CConfigurationChannelEvents.REGISTER.register { handler, _, server, channels ->
            if (FabricChannelSupport.registerConfigurationChannels(handler, channels)) {
                reconcileConfigurationSupport(server, handler)
            }
        }
        S2CConfigurationChannelEvents.UNREGISTER.register { handler, _, server, channels ->
            if (FabricChannelSupport.unregisterConfigurationChannels(handler, channels)) {
                reconcileConfigurationSupport(server, handler)
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            if (FabricChannelSupport.requiresDeferredOpen(player)) {
                deferOpen(server, player)
            } else {
                openIfSupported(server, player)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            close(server, handler.player.uuid, handler)
        }
        S2CPlayChannelEvents.REGISTER.register { handler, _, server, channels ->
            if (channels.any(outboundChannels::contains)) {
                openOrRefreshIfSupported(server, handler.player)
            }
        }
        S2CPlayChannelEvents.UNREGISTER.register { handler, _, server, channels ->
            if (channels.any(outboundChannels::contains)) {
                closeIfUnsupported(server, handler.player)
            }
        }
        ServerTickEvents.END_SERVER_TICK.register(::openDeferredConnections)
        ServerLifecycleEvents.SERVER_STOPPED.register(::clear)
    }

    private fun reconcileConfigurationSupport(
        server: MinecraftServer,
        handler: ServerConfigurationPacketListenerImpl,
    ) {
        val playerId = handler.owner.id
        if (!server.isSameThread) {
            server.execute { reconcileConfigurationSupport(server, handler) }
            return
        }
        val player = server.playerList.getPlayer(playerId) ?: return
        if (!FabricChannelSupport.belongsToConfigurationConnection(handler, player)) {
            return
        }
        if (!FabricChannelSupport.clientCanReceiveServerPayloads(player)) {
            close(server, playerId, player.connection)
        } else if (FabricChannelSupport.requiresDeferredOpen(player)) {
            deferOpen(server, player)
        } else {
            openOrRefreshIfSupported(server, player)
        }
    }

    private fun deferOpen(
        server: MinecraftServer,
        player: ServerPlayer,
        attemptsRemaining: Int = MAX_DEFERRED_OPEN_ATTEMPTS,
    ) {
        check(server.isSameThread) { "Emotify Fabric connections must be deferred on the main server thread" }
        deferredConnectionOpens.defer(player.uuid, player.connection, attemptsRemaining)
    }

    private fun openDeferredConnections(server: MinecraftServer) {
        check(server.isSameThread) { "Emotify Fabric connections must be opened on the main server thread" }
        FabricServerRuntime.drainCustomAssetVerifications(server)
        deferredConnectionOpens.drain { playerId, connectionIdentity, attemptsRemaining ->
            val player = server.playerList.getPlayer(playerId) ?: return@drain
            if (player.connection !== connectionIdentity) {
                return@drain
            }
            val status = openOrRefreshIfSupportedOnMainThread(server, player) ?: return@drain
            if (status != OutboundDeliveryStatus.SENT && attemptsRemaining > 1) {
                deferOpen(server, player, attemptsRemaining - 1)
            } else if (status != OutboundDeliveryStatus.SENT) {
                EmotifyFabric.LOGGER.warn(
                    "Emotify Fabric server hello remained undelivered for player {} after {} attempts",
                    playerId,
                    MAX_DEFERRED_OPEN_ATTEMPTS,
                )
            }
        }
    }

    private fun openIfSupported(server: MinecraftServer, player: ServerPlayer) {
        if (!server.isSameThread) {
            val playerId = player.uuid
            server.execute {
                server.playerList.getPlayer(playerId)?.let { currentPlayer ->
                    openIfSupported(server, currentPlayer)
                }
            }
            return
        }
        if (FabricServerConnectionRegistry.current(player.uuid, player.connection) != null) {
            return
        }
        val status = openOrRefreshIfSupportedOnMainThread(server, player) ?: return
        retryIfUndelivered(server, player, status)
    }

    private fun openNewConnection(
        server: MinecraftServer,
        player: ServerPlayer,
    ): OutboundDeliveryStatus {
        val state = FabricServerConnectionRegistry.open(player.uuid, player.connection)
        return try {
            FabricServerRuntime.open(server, player.uuid, state.connectionId)
        } catch (error: RuntimeException) {
            FabricServerConnectionRegistry.close(player.uuid, state)
            throw error
        }
    }

    private fun openOrRefreshIfSupported(server: MinecraftServer, player: ServerPlayer) {
        if (!server.isSameThread) {
            val playerId = player.uuid
            server.execute {
                server.playerList.getPlayer(playerId)?.let { currentPlayer ->
                    openOrRefreshIfSupported(server, currentPlayer)
                }
            }
            return
        }
        val status = openOrRefreshIfSupportedOnMainThread(server, player) ?: return
        retryIfUndelivered(server, player, status)
    }

    private fun retryIfUndelivered(
        server: MinecraftServer,
        player: ServerPlayer,
        status: OutboundDeliveryStatus,
    ) {
        if (status != OutboundDeliveryStatus.SENT) {
            deferOpen(server, player, MAX_DEFERRED_OPEN_ATTEMPTS - 1)
        }
    }

    private fun openOrRefreshIfSupportedOnMainThread(
        server: MinecraftServer,
        player: ServerPlayer,
    ): OutboundDeliveryStatus? {
        check(server.isSameThread) { "Emotify Fabric handshakes must run on the main server thread" }
        val existing = FabricServerConnectionRegistry.current(player.uuid)
        if (existing != null && !existing.belongsTo(player.connection)) {
            close(server, player.uuid, existing)
        }
        if (!FabricChannelSupport.clientCanReceiveServerPayloads(player)) return null
        val active = FabricServerConnectionRegistry.current(player.uuid, player.connection)
        return active?.let { state ->
            FabricServerRuntime.refreshServerHello(server, player.uuid, state.connectionId)
        } ?: openNewConnection(server, player)
    }

    private fun closeIfUnsupported(server: MinecraftServer, player: ServerPlayer) {
        if (!server.isSameThread) {
            val playerId = player.uuid
            server.execute {
                server.playerList.getPlayer(playerId)?.let { currentPlayer ->
                    closeIfUnsupported(server, currentPlayer)
                }
            }
            return
        }
        if (!FabricChannelSupport.clientCanReceiveServerPayloads(player)) {
            close(server, player.uuid, player.connection)
        }
    }

    private fun close(server: MinecraftServer, playerId: UUID, connectionIdentity: Any) {
        val expected = FabricServerConnectionRegistry.current(playerId) ?: return
        if (!expected.belongsTo(connectionIdentity)) {
            return
        }
        if (!server.isSameThread) {
            server.execute { close(server, playerId, expected) }
            return
        }
        close(server, playerId, expected)
    }

    private fun close(server: MinecraftServer, playerId: UUID, expected: FabricServerConnectionState) {
        if (FabricServerConnectionRegistry.current(playerId) !== expected) {
            return
        }
        FabricServerRuntime.close(server, playerId, expected.connectionId)
        FabricServerConnectionRegistry.close(playerId, expected)
    }

    private fun clear(server: MinecraftServer) {
        deferredConnectionOpens.clear()
        FabricServerRuntime.clear(server)
        val activeConnections = FabricServerConnectionRegistry.size
        FabricServerConnectionRegistry.clear()
        if (activeConnections > 0) {
            EmotifyFabric.LOGGER.debug(
                "Cleared {} Emotify Fabric connection states after server stop",
                activeConnections,
            )
        }
    }

    private const val MAX_DEFERRED_OPEN_ATTEMPTS = 3
}
