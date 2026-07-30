package me.whish.emotify.fabric.server

import java.util.UUID
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.network.FabricChannelSupport
import me.whish.emotify.fabric.runtime.FabricProtocol
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.server.core.AudienceTraversalOutcome
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.EmotifyServerEngine
import me.whish.emotify.server.core.GlobalSelectionIngressAdmission
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.OutboundAttempt
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.PlayerSnapshot
import me.whish.emotify.server.core.RejectionDispatch
import me.whish.emotify.server.core.ServerHandshakeTransition
import me.whish.emotify.server.core.ServerHelloResult
import me.whish.emotify.server.core.ServerSelectionPolicy
import me.whish.emotify.server.core.ServerSelectionResult
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

object FabricServerRuntime {
    private val selectionIngressBudget = GlobalSelectionIngressBudget(
        timeSource = SystemMonotonicTimeSource,
    )
    private val diagnostics = TokenBucket(
        capacity = DIAGNOSTIC_BURST_CAPACITY,
        refillTokensPerSecond = DIAGNOSTIC_TOKENS_PER_SECOND,
        timeSource = SystemMonotonicTimeSource,
    )
    private val selectionPolicy = ServerSelectionPolicy(
        enabled = true,
        catalog = FabricProtocol.serverHello.emotionCatalog,
        allowedEmotions = FabricProtocol.serverHello.emotionCatalog,
    )
    private var activeRuntime: Runtime? = null

    fun tryAcquireSelectionIngress(): GlobalSelectionIngressAdmission = selectionIngressBudget.tryAcquire()

    fun open(server: MinecraftServer, playerId: UUID, connectionId: ConnectionId): OutboundDeliveryStatus {
        check(server.isSameThread) { "Emotify sessions must be opened on the main server thread" }
        val connection = ConnectionKey(playerId, connectionId)
        val result = runtimeForOpen(server).engine.open(connection)
        reportOutboundFailure("server hello", playerId, connectionId, result.hello)
        when (result.hello.status) {
            OutboundDeliveryStatus.SENT -> EmotifyFabric.LOGGER.debug(
                "Emotify Fabric handshake pending for player {} on connection {}",
                playerId,
                connectionId.value,
            )
            OutboundDeliveryStatus.UNAVAILABLE -> EmotifyFabric.LOGGER.debug(
                "Emotify Fabric server hello unavailable for player {} on connection {}",
                playerId,
                connectionId.value,
            )
            OutboundDeliveryStatus.FAILED -> Unit
        }
        return result.hello.status
    }

    fun receiveClientHello(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: ConnectionId,
        hello: ClientHello,
    ) {
        check(server.isSameThread) { "Emotify handshakes must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        val connection = ConnectionKey(playerId, connectionId)
        when (val result = runtime.engine.receiveClientHello(connection, hello)) {
            ServerHelloResult.StaleConnection -> Unit
            is ServerHelloResult.Processed -> when (result.transition) {
                ServerHandshakeTransition.SUPPORTED -> EmotifyFabric.LOGGER.debug(
                    "Emotify Fabric handshake supported for player {} on connection {}",
                    playerId,
                    connectionId.value,
                )
                ServerHandshakeTransition.UNSUPPORTED -> {
                    if (diagnostics.tryConsume()) {
                        EmotifyFabric.LOGGER.warn(
                            "Emotify Fabric handshake unsupported for player {} on connection {}: {}",
                            playerId,
                            connectionId.value,
                            result.state,
                        )
                    }
                }
                ServerHandshakeTransition.NO_CHANGE -> Unit
            }
        }
    }

    fun refreshServerHello(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: ConnectionId,
    ): OutboundDeliveryStatus {
        check(server.isSameThread) { "Emotify handshakes must be refreshed on the main server thread" }
        val runtime = activeRuntime(server) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val connection = ConnectionKey(playerId, connectionId)
        val attempt = runtime.engine.refreshServerHello(connection)
        reportOutboundFailure(
            "server hello refresh",
            playerId,
            connectionId,
            attempt,
        )
        return attempt.status
    }

    fun select(
        server: MinecraftServer,
        player: ServerPlayer,
        state: FabricServerConnectionState,
        emotionId: EmotionId,
    ) {
        check(server.isSameThread) { "Emotify selections must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        if (
            FabricServerConnectionRegistry.current(player.uuid) !== state ||
            !FabricChannelSupport.clientCanReceiveServerPayloads(player)
        ) {
            return
        }
        val runtimeEntityId = RuntimeEntityId.parse(player.id) ?: return
        val connection = ConnectionKey(player.uuid, state.connectionId)
        val snapshot = PlayerSnapshot(
            connection = connection,
            entityId = runtimeEntityId,
            alive = player.isAlive,
            spectator = player.isSpectator,
            invisible = player.isInvisible,
            dimensionId = runtime.dimensionOrdinals.resolve(player.level().dimension()),
            regionKey = ChunkPos.asLong(player.blockX shr 4, player.blockZ shr 4),
        )
        reportSelectionFailures(player.uuid, state.connectionId, runtime.engine.select(snapshot, emotionId))
    }

    fun close(server: MinecraftServer, playerId: UUID, connectionId: ConnectionId) {
        check(server.isSameThread) { "Emotify sessions must be closed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        runtime.engine.close(ConnectionKey(playerId, connectionId))
    }

    fun clear(server: MinecraftServer) {
        check(server.isSameThread) { "Emotify server state must be cleared on the main server thread" }
        val runtime = activeRuntime
        if (runtime != null && runtime.server === server) {
            runtime.engine.clear()
            runtime.dimensionOrdinals.clear()
            activeRuntime = null
        } else if (runtime == null) {
            selectionIngressBudget.reset()
        }
        diagnostics.reset()
    }

    private fun runtimeForOpen(server: MinecraftServer): Runtime {
        val existing = activeRuntime
        if (existing != null) {
            check(existing.server === server) { "Emotify server runtime was not cleared before a new server started" }
            return existing
        }
        return Runtime(
            server,
            DimensionOrdinalRegistry(),
            EmotifyServerEngine(
                serverHello = FabricProtocol.serverHello,
                selectionPolicy = selectionPolicy,
                timeSource = SystemMonotonicTimeSource,
                audiencePort = FabricAudiencePort(server),
                outboundTransport = FabricOutboundTransport(server),
                ingressBudget = selectionIngressBudget,
            ),
        ).also { created -> activeRuntime = created }
    }

    private fun activeRuntime(server: MinecraftServer): Runtime? =
        activeRuntime?.takeIf { runtime -> runtime.server === server }

    private fun reportSelectionFailures(
        playerId: UUID,
        connectionId: ConnectionId,
        result: ServerSelectionResult,
    ) {
        when (result) {
            is ServerSelectionResult.Ignored -> Unit
            is ServerSelectionResult.Rejected -> {
                val dispatch = result.dispatch as? RejectionDispatch.Attempted ?: return
                reportOutboundFailure("selection rejection", playerId, connectionId, dispatch.outbound)
            }
            is ServerSelectionResult.Published -> reportDeliveryFailures(
                playerId,
                connectionId,
                result.traversal,
                result.firstSendFailure,
            )
            is ServerSelectionResult.Undelivered -> reportDeliveryFailures(
                playerId,
                connectionId,
                result.traversal,
                result.firstSendFailure,
            )
        }
    }

    private fun reportDeliveryFailures(
        playerId: UUID,
        connectionId: ConnectionId,
        traversal: AudienceTraversalOutcome,
        firstSendFailure: RuntimeException?,
    ) {
        val failure = (traversal as? AudienceTraversalOutcome.Failed)?.failure ?: firstSendFailure ?: return
        if (diagnostics.tryConsume()) {
            EmotifyFabric.LOGGER.warn(
                "Failed to publish Emotify Fabric play for player {} on connection {}",
                playerId,
                connectionId.value,
                failure,
            )
        }
    }

    private fun reportOutboundFailure(
        operation: String,
        playerId: UUID,
        connectionId: ConnectionId,
        attempt: OutboundAttempt,
    ) {
        if (attempt.status != OutboundDeliveryStatus.FAILED || !diagnostics.tryConsume()) {
            return
        }
        val failure = attempt.failure
        if (failure == null) {
            EmotifyFabric.LOGGER.warn(
                "Failed to deliver Emotify Fabric {} for player {} on connection {}",
                operation,
                playerId,
                connectionId.value,
            )
        } else {
            EmotifyFabric.LOGGER.warn(
                "Failed to deliver Emotify Fabric {} for player {} on connection {}",
                operation,
                playerId,
                connectionId.value,
                failure,
            )
        }
    }

    private data class Runtime(
        val server: MinecraftServer,
        val dimensionOrdinals: DimensionOrdinalRegistry,
        val engine: EmotifyServerEngine,
    )

    private const val DIAGNOSTIC_BURST_CAPACITY = 8
    private const val DIAGNOSTIC_TOKENS_PER_SECOND = 2
}
