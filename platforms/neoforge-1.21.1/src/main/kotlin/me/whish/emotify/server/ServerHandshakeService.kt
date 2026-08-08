package me.whish.emotify.server

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import me.whish.emotify.Emotify
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.runtime.EmotifyProtocol
import me.whish.emotify.server.core.AudienceTraversalOutcome
import me.whish.emotify.server.core.AudienceBudget
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.EmotifyServerEngine
import me.whish.emotify.server.core.GlobalSelectionIngressAdmission
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.GlobalSelectionIngressLease
import me.whish.emotify.server.core.OutboundAttempt
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.PlayerSnapshot
import me.whish.emotify.server.core.RejectionDispatch
import me.whish.emotify.server.core.ServerHandshakeTransition
import me.whish.emotify.server.core.ServerHelloResult
import me.whish.emotify.server.core.ServerSelectionResult
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos

object ServerHandshakeService {
    private val connectionIds = AtomicLong()
    private val selectionIngressBudget = GlobalSelectionIngressBudget(
        timeSource = SystemMonotonicTimeSource,
    )
    private val diagnostics = TokenBucket(
        capacity = DIAGNOSTIC_BURST_CAPACITY,
        refillTokensPerSecond = DIAGNOSTIC_TOKENS_PER_SECOND,
        timeSource = SystemMonotonicTimeSource,
    )
    private var activeRuntime: Runtime? = null

    fun initialize(server: MinecraftServer) {
        check(server.isSameThread) { "Emotify runtime must be initialized on the main server thread" }
        runtimeFor(server)
    }

    fun nextConnectionId(): Long = connectionIds.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "Server connection ID space is exhausted" }
        current + 1L
    }

    fun tryAcquireSelectionIngress(): GlobalSelectionIngressLease? =
        when (val admission = selectionIngressBudget.tryAcquire()) {
            is GlobalSelectionIngressAdmission.Admitted -> admission.lease
            GlobalSelectionIngressAdmission.OutstandingLimitReached,
            GlobalSelectionIngressAdmission.RateLimited,
            -> null
        }

    fun open(server: MinecraftServer, playerId: UUID, connectionId: Long): OutboundDeliveryStatus {
        check(server.isSameThread) { "Emotify sessions must be opened on the main server thread" }
        val connection = ConnectionKey(playerId, ConnectionId.of(connectionId))
        val result = runtimeFor(server).engine.open(connection)
        reportOutboundFailure("server hello", playerId, connectionId, result.hello)
        Emotify.LOGGER.debug(
            "Emotify handshake pending for player {} on connection {}",
            playerId,
            connectionId,
        )
        return result.hello.status
    }

    fun refreshServerHello(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: Long,
    ): OutboundDeliveryStatus {
        check(server.isSameThread) { "Emotify handshakes must be refreshed on the main server thread" }
        val runtime = activeRuntime(server) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val attempt = runtime.engine.refreshServerHello(ConnectionKey(playerId, ConnectionId.of(connectionId)))
        reportOutboundFailure("server hello retry", playerId, connectionId, attempt)
        return attempt.status
    }

    fun reportServerHelloRetryExhausted(
        playerId: UUID,
        connectionId: Long,
        status: OutboundDeliveryStatus,
    ) {
        if (diagnostics.tryConsume()) {
            Emotify.LOGGER.warn(
                "Emotify server hello remained undelivered for player {} on connection {} after retries: {}",
                playerId,
                connectionId,
                status,
            )
        }
    }

    fun receive(server: MinecraftServer, playerId: UUID, connectionId: Long, hello: ClientHello) {
        check(server.isSameThread) { "Emotify handshakes must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        val connection = ConnectionKey(playerId, ConnectionId.of(connectionId))
        when (val result = runtime.engine.receiveClientHello(connection, hello)) {
            ServerHelloResult.StaleConnection -> Unit
            is ServerHelloResult.Processed -> when (result.transition) {
                ServerHandshakeTransition.SUPPORTED -> Emotify.LOGGER.debug(
                    "Emotify handshake supported for player {} on connection {}",
                    playerId,
                    connectionId,
                )
                ServerHandshakeTransition.UNSUPPORTED -> {
                    if (diagnostics.tryConsume()) {
                        Emotify.LOGGER.warn(
                            "Emotify handshake unsupported for player {} on connection {}: {}",
                            playerId,
                            connectionId,
                            result.state,
                        )
                    }
                }
                ServerHandshakeTransition.NO_CHANGE -> Unit
            }
        }
    }

    fun select(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: Long,
        worldEpoch: Long,
        emotionId: EmotionId,
    ) {
        check(server.isSameThread) { "Emotify selections must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        val player = server.playerList.getPlayer(playerId) ?: return
        val channel = player.connection.connection.channel()
        val activeConnectionId = channel.attr(ConnectionAttributes.serverConnectionId).get()
        val activeWorldEpoch = channel.attr(ConnectionAttributes.serverWorldEpoch).get()?.current()
        if (activeConnectionId != connectionId || activeWorldEpoch != worldEpoch) {
            return
        }
        if (!EmotifyChannels.clientCanReceiveServerPayloads { type -> player.connection.hasChannel(type) }) {
            return
        }

        val runtimeEntityId = RuntimeEntityId.parse(player.id) ?: return
        val connection = ConnectionKey(playerId, ConnectionId.of(connectionId))
        val snapshot = PlayerSnapshot(
            connection = connection,
            entityId = runtimeEntityId,
            alive = player.isAlive,
            spectator = player.isSpectator,
            invisible = player.isInvisible,
            dimensionId = runtime.dimensionOrdinals.resolve(player.level().dimension()),
            regionKey = ChunkPos.asLong(player.blockX shr 4, player.blockZ shr 4),
        )
        reportSelectionFailures(playerId, connectionId, runtime.engine.select(snapshot, emotionId))
    }

    fun selectCustom(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: Long,
        worldEpoch: Long,
        selection: CustomEmotionSelection,
    ) {
        check(server.isSameThread) { "Emotify selections must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        val player = server.playerList.getPlayer(playerId) ?: return
        val channel = player.connection.connection.channel()
        val activeConnectionId = channel.attr(ConnectionAttributes.serverConnectionId).get()
        val activeWorldEpoch = channel.attr(ConnectionAttributes.serverWorldEpoch).get()?.current()
        if (activeConnectionId != connectionId || activeWorldEpoch != worldEpoch) {
            return
        }
        if (!EmotifyChannels.clientCanReceiveCustomEmojis { type -> player.connection.hasChannel(type) }) {
            return
        }
        val runtimeEntityId = RuntimeEntityId.parse(player.id) ?: return
        val connection = ConnectionKey(playerId, ConnectionId.of(connectionId))
        val snapshot = PlayerSnapshot(
            connection = connection,
            entityId = runtimeEntityId,
            alive = player.isAlive,
            spectator = player.isSpectator,
            invisible = player.isInvisible,
            dimensionId = runtime.dimensionOrdinals.resolve(player.level().dimension()),
            regionKey = ChunkPos.asLong(player.blockX shr 4, player.blockZ shr 4),
        )
        reportSelectionFailures(playerId, connectionId, runtime.engine.selectCustom(snapshot, selection))
    }

    fun receiveCustomAssetChunk(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: Long,
        chunk: CustomEmojiAssetChunk,
    ) {
        check(server.isSameThread) { "Emotify custom asset chunks must be processed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        val player = server.playerList.getPlayer(playerId) ?: return
        val activeConnectionId = player.connection.connection.channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get()
        if (
            activeConnectionId != connectionId ||
            !EmotifyChannels.serverCanReceiveLosslessCustomEmojis { type -> player.connection.hasChannel(type) }
        ) {
            return
        }
        runtime.engine.receiveCustomAssetChunk(
            ConnectionKey(playerId, ConnectionId.of(connectionId)),
            chunk,
        )
    }

    fun close(server: MinecraftServer, playerId: UUID, connectionId: Long) {
        check(server.isSameThread) { "Emotify sessions must be closed on the main server thread" }
        val runtime = activeRuntime(server) ?: return
        runtime.engine.close(ConnectionKey(playerId, ConnectionId.of(connectionId)))
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

    private fun runtimeFor(server: MinecraftServer): Runtime {
        val existing = activeRuntime
        if (existing != null) {
            check(existing.server === server) { "Emotify server runtime was not cleared before a new server started" }
            return existing
        }

        val settings = EmotifyServerConfig.snapshot()
        val configuration = settings.configuration(EmotifyProtocol.serverHello)
        selectionIngressBudget.reconfigure(settings.selectionIngressLimits)
        val created = Runtime(
            server,
            DimensionOrdinalRegistry(),
            EmotifyServerEngine(
                serverHello = configuration.serverHello,
                selectionPolicy = configuration.selectionPolicy,
                timeSource = SystemMonotonicTimeSource,
                audiencePort = NeoForgeAudiencePort(server),
                outboundTransport = NeoForgeOutboundTransport(server),
                audienceBudget = AudienceBudget(settings.audienceBudgetLimits, SystemMonotonicTimeSource),
                ingressBudget = selectionIngressBudget,
                featureRegistry = EmotifyProtocolFeatures.registry,
                audiencePolicy = configuration.audiencePolicy,
            ),
        )
        activeRuntime = created
        return created
    }

    private fun activeRuntime(server: MinecraftServer): Runtime? =
        activeRuntime?.takeIf { runtime -> runtime.server === server }

    private fun reportSelectionFailures(
        playerId: UUID,
        connectionId: Long,
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
        connectionId: Long,
        traversal: AudienceTraversalOutcome,
        firstSendFailure: RuntimeException?,
    ) {
        val failure = (traversal as? AudienceTraversalOutcome.Failed)?.failure ?: firstSendFailure ?: return
        if (diagnostics.tryConsume()) {
            Emotify.LOGGER.warn(
                "Failed to publish Emotify play for player {} on connection {}",
                playerId,
                connectionId,
                failure,
            )
        }
    }

    private fun reportOutboundFailure(
        operation: String,
        playerId: UUID,
        connectionId: Long,
        attempt: OutboundAttempt,
    ) {
        if (attempt.status != OutboundDeliveryStatus.FAILED || !diagnostics.tryConsume()) {
            return
        }
        val failure = attempt.failure
        if (failure == null) {
            Emotify.LOGGER.warn(
                "Failed to deliver Emotify {} for player {} on connection {}",
                operation,
                playerId,
                connectionId,
            )
        } else {
            Emotify.LOGGER.warn(
                "Failed to deliver Emotify {} for player {} on connection {}",
                operation,
                playerId,
                connectionId,
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
