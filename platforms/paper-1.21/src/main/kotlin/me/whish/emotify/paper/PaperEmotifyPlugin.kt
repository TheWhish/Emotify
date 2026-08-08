package me.whish.emotify.paper

import java.util.logging.Level
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.paper.config.BukkitPaperConfigLoader
import me.whish.emotify.paper.config.PaperConfigLoadResult
import me.whish.emotify.paper.config.PaperConfigurationApplyResult
import me.whish.emotify.paper.config.PaperConfigurationApplyTransaction
import me.whish.emotify.paper.config.PaperConfigurationState
import me.whish.emotify.paper.config.PaperReloadCoordinator
import me.whish.emotify.paper.config.PaperRuntimeConfig
import me.whish.emotify.paper.network.PaperProtocolChannels
import me.whish.emotify.paper.network.PaperProtocolV1Bridge
import me.whish.emotify.paper.runtime.PaperClientHelloIngress
import me.whish.emotify.paper.runtime.PaperConnectionIngress
import me.whish.emotify.paper.runtime.PaperCustomSelectionIngress
import me.whish.emotify.paper.runtime.PaperDiagnosticGate
import me.whish.emotify.paper.runtime.PaperDimensionOrdinalRegistry
import me.whish.emotify.paper.runtime.PaperIngressGate
import me.whish.emotify.paper.runtime.PaperIngressLease
import me.whish.emotify.paper.runtime.PaperReloadGate
import me.whish.emotify.paper.runtime.PaperPolicyRefreshBatchResult
import me.whish.emotify.paper.runtime.PaperPolicyRefreshDispatcher
import me.whish.emotify.paper.runtime.PaperServerOpenResult
import me.whish.emotify.paper.runtime.PaperServerRuntime
import me.whish.emotify.paper.runtime.PaperSelectionIngress
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.AudienceTraversalOutcome
import me.whish.emotify.server.core.AudienceBudget
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.GlobalSelectionIngressLease
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.OutboundAttempt
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.RejectionDispatch
import me.whish.emotify.server.core.ServerHandshakeTransition
import me.whish.emotify.server.core.ServerHelloResult
import me.whish.emotify.server.core.ServerSelectionPolicy
import me.whish.emotify.server.core.ServerSelectionResult
import me.whish.emotify.server.core.ServerRuntimeConfiguration
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.WireDecodeException
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.event.player.PlayerUnregisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

class PaperEmotifyPlugin : JavaPlugin(), Listener, PluginMessageListener {
    private val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
    private lateinit var connections: PaperConnectionIngress
    private lateinit var runtime: PaperServerRuntime
    private lateinit var snapshotFactory: BukkitPaperPlayerSnapshotFactory
    private lateinit var dimensions: PaperDimensionOrdinalRegistry
    private lateinit var diagnosticGate: PaperDiagnosticGate
    private lateinit var ingressGate: PaperIngressGate
    private lateinit var globalSelectionBudget: GlobalSelectionIngressBudget
    private lateinit var configurationState: PaperConfigurationState
    private lateinit var reloadCoordinator: PaperReloadCoordinator
    private lateinit var policyRefreshDispatcher: PaperPolicyRefreshDispatcher
    private lateinit var configurationApplyTransaction: PaperConfigurationApplyTransaction<
        PaperRuntimeConfig,
        PaperConfigurationApplyResult,
    >

    override fun onEnable() {
        check(server.isPrimaryThread) { "Emotify Paper must be enabled on the primary server thread" }
        saveDefaultConfig()
        val configurationLoader = BukkitPaperConfigLoader(
            dataFolder.resolve("config.yml"),
            BuiltInEmotionCatalog.catalog,
        )
        val configuration = loadInitialConfiguration(configurationLoader)
        val serverConfiguration = serverConfiguration(configuration)
        configurationState = PaperConfigurationState(configuration)
        val ingressLimits = configuration.ingress.globalSelectionLimits
        globalSelectionBudget = GlobalSelectionIngressBudget(
            maxOutstanding = ingressLimits.maximumOutstanding,
            requestBurstCapacity = ingressLimits.requestBurstCapacity,
            requestRefillTokensPerSecond = ingressLimits.requestRefillTokensPerSecond,
            timeSource = SystemMonotonicTimeSource,
        )
        ingressGate = PaperIngressGate(configuration.ingress.maximumQueuedMainThreadTasks)
        connections = PaperConnectionIngress(
            BuiltInEmotionCatalog.catalog,
            SystemMonotonicTimeSource,
            globalSelectionBudget,
        )
        dimensions = PaperDimensionOrdinalRegistry()
        diagnosticGate = PaperDiagnosticGate(
            DIAGNOSTIC_BURST_CAPACITY,
            DIAGNOSTIC_REFILL_TOKENS_PER_SECOND,
            SystemMonotonicTimeSource,
        )
        val broadcastLimits = configuration.broadcast.budgetLimits
        runtime = PaperServerRuntime(
            serverConfiguration.serverHello,
            serverConfiguration.selectionPolicy,
            SystemMonotonicTimeSource,
            BukkitPaperAudiencePort(server, connections),
            BukkitPaperOutboundTransport(this, connections),
            { server.isPrimaryThread },
            globalSelectionBudget,
            AudienceBudget(
                globalCapacity = broadcastLimits.globalCapacity,
                globalRefillTokensPerSecond = broadcastLimits.globalRefillTokensPerSecond,
                regionCapacity = broadcastLimits.regionCapacity,
                regionRefillTokensPerSecond = broadcastLimits.regionRefillTokensPerSecond,
                maxRegions = broadcastLimits.maximumRegions,
                timeSource = SystemMonotonicTimeSource,
            ),
            configuration.broadcast.audience,
            EmotifyProtocolFeatures.registry,
        )
        snapshotFactory = BukkitPaperPlayerSnapshotFactory(server, connections, dimensions)
        policyRefreshDispatcher = PaperPolicyRefreshDispatcher(this, reportBatch = ::reportPolicyRefreshBatch)
        policyRefreshDispatcher.start()
        configurationApplyTransaction = PaperConfigurationApplyTransaction(
            configurationState::current,
            ::applyConfigurationUnsafe,
        )
        reloadCoordinator = PaperReloadCoordinator(
            this,
            configurationLoader,
            configurationState,
            PaperReloadGate(SystemMonotonicTimeSource),
            ::applyConfiguration,
        )
        requireNotNull(getCommand("emotify")) { "Emotify command is missing from plugin.yml" }
            .setExecutor(reloadCoordinator)

        PaperProtocolChannels.outgoing.forEach { channel ->
            server.messenger.registerOutgoingPluginChannel(this, channel)
        }
        PaperProtocolChannels.advertisedIncoming.forEach { channel ->
            server.messenger.registerIncomingPluginChannel(this, channel, this)
        }
        server.pluginManager.registerEvents(this, this)
        server.onlinePlayers.forEach(::beginConnection)
        logger.info("Emotify Paper/Purpur Protocol 1 production adapter enabled")
    }

    override fun onDisable() {
        check(server.isPrimaryThread) { "Emotify Paper must be disabled on the primary server thread" }
        if (::reloadCoordinator.isInitialized) {
            reloadCoordinator.shutdown()
        }
        if (::policyRefreshDispatcher.isInitialized) {
            policyRefreshDispatcher.clear()
        }
        server.scheduler.cancelTasks(this)
        if (::runtime.isInitialized) {
            runtime.clear()
        }
        if (::dimensions.isInitialized) {
            dimensions.clear()
        }
        if (::connections.isInitialized) {
            connections.clear()
        }
        if (::ingressGate.isInitialized) {
            ingressGate.clear()
        }
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
        HandlerList.unregisterAll(this as Listener)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        beginConnection(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val connection = connections.current(event.player.uniqueId, event.player) ?: return
        connections.close(connection)
        runtime.close(connection)
    }

    @EventHandler
    fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (
            event.channel in PaperProtocolChannels.outgoing &&
            PaperProtocolChannels.requiresBukkitSubscription(event.channel)
        ) {
            val connection = connections.current(event.player.uniqueId, event.player) ?: return
            connections.registerOutgoingChannel(connection, event.channel)
            tryOpen(event.player, connection, refreshExisting = true)
        }
    }

    @EventHandler
    fun onPlayerUnregisterChannel(event: PlayerUnregisterChannelEvent) {
        if (
            event.channel in PaperProtocolChannels.outgoing &&
            PaperProtocolChannels.requiresBukkitSubscription(event.channel)
        ) {
            val connection = connections.current(event.player.uniqueId, event.player) ?: return
            connections.unregisterOutgoingChannel(connection, event.channel)
        }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!PaperProtocolChannels.acceptsIncoming(channel)) {
            return
        }
        val connection = connections.current(player.uniqueId, player) ?: return
        try {
            when (channel) {
                ProtocolV1Channels.CLIENT_HELLO -> when (
                    val admission = connections.admitClientHello(connection) {
                        PaperProtocolV1Bridge.decodeClientHello(message)
                    }
                ) {
                    is PaperClientHelloIngress.Admitted -> dispatchClientHello(
                        connection,
                        admission.hello,
                    )
                    PaperClientHelloIngress.DUPLICATE_OR_BLOCKED,
                    PaperClientHelloIngress.PROTOCOL_INACTIVE,
                    PaperClientHelloIngress.RATE_LIMITED,
                    PaperClientHelloIngress.STALE_CONNECTION,
                    -> Unit
                }
                ProtocolV1Channels.SELECT -> when (
                    val admission = connections.admitSelection(connection) {
                        PaperProtocolV1Bridge.decodeSelection(message)
                    }
                ) {
                    is PaperSelectionIngress.Admitted -> dispatchSelection(
                        connection,
                        admission.selection,
                        admission.lease,
                    )
                    PaperSelectionIngress.RATE_LIMITED,
                    PaperSelectionIngress.PROTOCOL_INACTIVE,
                    PaperSelectionIngress.STALE_CONNECTION,
                    PaperSelectionIngress.UNKNOWN_EMOTION,
                    -> Unit
                }
                ProtocolV1Channels.CUSTOM_SELECT -> when (
                    val admission = connections.admitCustomSelection(connection) {
                        PaperProtocolV1Bridge.decodeCustomSelection(message)
                    }
                ) {
                    is PaperCustomSelectionIngress.Admitted -> dispatchCustomSelection(
                        connection,
                        admission.selection,
                        admission.lease,
                    )
                    PaperCustomSelectionIngress.RATE_LIMITED,
                    PaperCustomSelectionIngress.PROTOCOL_INACTIVE,
                    PaperCustomSelectionIngress.STALE_CONNECTION,
                    -> Unit
                }
                ProtocolV1Channels.CUSTOM_ASSET_CHUNK -> when (
                    val admission = connections.admitCustomAssetChunk(connection, message.size) {
                        PaperProtocolV1Bridge.decodeCustomAssetChunk(message)
                    }
                ) {
                    is me.whish.emotify.paper.runtime.PaperCustomAssetChunkIngress.Admitted ->
                        runtime.receiveCustomAssetChunk(connection, admission.chunk)
                    me.whish.emotify.paper.runtime.PaperCustomAssetChunkIngress.INVALID_SIZE,
                    me.whish.emotify.paper.runtime.PaperCustomAssetChunkIngress.PROTOCOL_INACTIVE,
                    me.whish.emotify.paper.runtime.PaperCustomAssetChunkIngress.RATE_LIMITED,
                    me.whish.emotify.paper.runtime.PaperCustomAssetChunkIngress.STALE_CONNECTION,
                    -> Unit
                }
            }
        } catch (exception: WireDecodeException) {
            logMalformedPayload(channel, exception)
        }
    }

    private fun beginConnection(player: Player) {
        check(server.isPrimaryThread) { "Paper connection lifecycle must run on the primary server thread" }
        scheduleOpen(connections.begin(player.uniqueId, player, player.listeningPluginChannels))
    }

    private fun scheduleOpen(
        connection: ConnectionKey,
        attemptsRemaining: Int = MAX_SERVER_HELLO_ATTEMPTS,
    ) {
        require(attemptsRemaining > 0) { "Paper server hello attempts must be positive: $attemptsRemaining" }
        server.scheduler.runTask(this, Runnable {
            if (!connections.isActive(connection)) {
                return@Runnable
            }
            server.getPlayer(connection.playerId)
                ?.takeIf { player -> player.isOnline }
                ?.let { player -> tryOpen(player, connection, refreshExisting = false, attemptsRemaining) }
        })
    }

    private fun tryOpen(
        player: Player,
        connection: ConnectionKey,
        refreshExisting: Boolean,
        attemptsRemaining: Int = MAX_SERVER_HELLO_ATTEMPTS,
    ) {
        if (!server.isPrimaryThread) {
            scheduleOpen(connection, attemptsRemaining)
            return
        }
        if (!connections.isActive(connection, player)) {
            return
        }
        if (!connections.supportsAllOutgoingChannels(connection)) {
            return
        }
        when (val result = runtime.open(connection)) {
            PaperServerOpenResult.Opened -> {
                if (connections.activateProtocol(connection)) {
                    logger.fine("Emotify Paper handshake opened for ${player.uniqueId}")
                } else {
                    runtime.close(connection)
                }
            }
            PaperServerOpenResult.AlreadyOpen -> {
                connections.activateProtocol(connection)
                if (refreshExisting) {
                    reportOutboundFailure("server policy refresh", connection, runtime.refresh(connection))
                }
            }
            is PaperServerOpenResult.Undelivered -> if (attemptsRemaining > 1) {
                scheduleOpen(connection, attemptsRemaining - 1)
            } else {
                reportOutboundFailure("server hello", connection, result.outbound)
            }
        }
    }

    private fun dispatchClientHello(connection: ConnectionKey, hello: ClientHello) {
        dispatch(connection) {
            when (val result = runtime.receiveClientHello(connection, hello)) {
                ServerHelloResult.StaleConnection -> Unit
                is ServerHelloResult.Processed -> when (result.transition) {
                    ServerHandshakeTransition.SUPPORTED -> logger.fine(
                        "Emotify Paper handshake supported for ${connection.playerId}",
                    )
                    ServerHandshakeTransition.UNSUPPORTED -> if (diagnosticGate.tryAdmit()) {
                        logger.warning("Emotify Paper handshake rejected incompatible client ${connection.playerId}")
                    }
                    ServerHandshakeTransition.NO_CHANGE -> Unit
                }
            }
        }
    }

    private fun dispatchSelection(
        connection: ConnectionKey,
        selection: EmotionSelection,
        selectionLease: GlobalSelectionIngressLease,
    ) {
        dispatch(connection, selectionLease) {
            val snapshot = snapshotFactory.create(connection) ?: return@dispatch
            reportSelectionResult(connection, runtime.select(snapshot, selection))
        }
    }

    private fun dispatchCustomSelection(
        connection: ConnectionKey,
        selection: CustomEmotionSelection,
        selectionLease: GlobalSelectionIngressLease,
    ) {
        dispatch(connection, selectionLease) {
            val snapshot = snapshotFactory.create(connection) ?: return@dispatch
            reportSelectionResult(connection, runtime.selectCustom(snapshot, selection))
        }
    }

    private fun reportSelectionResult(connection: ConnectionKey, result: ServerSelectionResult) {
        when (result) {
            is ServerSelectionResult.Ignored -> Unit
            is ServerSelectionResult.Rejected -> {
                val dispatch = result.dispatch as? RejectionDispatch.Attempted ?: return
                reportOutboundFailure("selection rejection", connection, dispatch.outbound)
            }
            is ServerSelectionResult.Published -> reportDeliveryFailure(
                connection,
                result.traversal,
                result.firstSendFailure,
            )
            is ServerSelectionResult.Undelivered -> reportDeliveryFailure(
                connection,
                result.traversal,
                result.firstSendFailure,
            )
        }
    }

    private fun reportDeliveryFailure(
        connection: ConnectionKey,
        traversal: AudienceTraversalOutcome,
        firstSendFailure: RuntimeException?,
    ) {
        val failure = (traversal as? AudienceTraversalOutcome.Failed)?.failure ?: firstSendFailure ?: return
        logRuntimeFailure(
            "Failed to publish Emotify play for ${connection.playerId}",
            failure,
        )
    }

    private fun reportOutboundFailure(
        operation: String,
        connection: ConnectionKey,
        attempt: OutboundAttempt,
    ) {
        if (attempt.status == OutboundDeliveryStatus.UNAVAILABLE) {
            logger.fine("Emotify Paper $operation channel unavailable for ${connection.playerId}")
            return
        }
        if (attempt.status != OutboundDeliveryStatus.FAILED || !diagnosticGate.tryAdmit()) {
            return
        }
        val failure = attempt.failure
        if (failure == null) {
            logger.warning("Failed to deliver Emotify $operation for ${connection.playerId}")
        } else {
            logger.log(Level.SEVERE, "Failed to deliver Emotify $operation for ${connection.playerId}", failure)
        }
    }

    private fun dispatch(
        connection: ConnectionKey,
        selectionLease: GlobalSelectionIngressLease? = null,
        task: () -> Unit,
    ) {
        if (server.isPrimaryThread) {
            runInbound(connection, task, selectionLease, null)
            return
        }
        val ingressLease = ingressGate.tryAcquire(connection)
        if (ingressLease == null) {
            selectionLease?.release()
            return
        }
        try {
            server.scheduler.runTask(this, Runnable {
                runInbound(connection, task, selectionLease, ingressLease)
            })
        } catch (exception: RuntimeException) {
            ingressLease.release()
            selectionLease?.release()
            logRuntimeFailure(
                "Failed to enqueue Emotify Paper ingress for ${connection.playerId}",
                exception,
            )
        }
    }

    private fun runInbound(
        connection: ConnectionKey,
        task: () -> Unit,
        selectionLease: GlobalSelectionIngressLease?,
        ingressLease: PaperIngressLease?,
    ) {
        try {
            if (connections.isProtocolActive(connection)) {
                task()
            }
        } catch (exception: RuntimeException) {
            logRuntimeFailure(
                "Failed to process Emotify Paper ingress for ${connection.playerId}",
                exception,
            )
        } finally {
            try {
                ingressLease?.release()
            } finally {
                selectionLease?.release()
            }
        }
    }

    private fun logMalformedPayload(channel: String, exception: WireDecodeException) {
        if (logger.isLoggable(Level.FINE) && diagnosticGate.tryAdmit()) {
            logger.fine("Dropped malformed Emotify payload on $channel: ${exception.violation}")
        }
    }

    private fun logRuntimeFailure(message: String, exception: RuntimeException) {
        if (diagnosticGate.tryAdmit()) {
            logger.log(Level.SEVERE, message, exception)
        }
    }

    private fun loadInitialConfiguration(loader: BukkitPaperConfigLoader): PaperRuntimeConfig =
        when (val result = loader.load()) {
            is PaperConfigLoadResult.Loaded -> result.config
            is PaperConfigLoadResult.Invalid -> error(
                "Emotify configuration is invalid: ${result.violations.joinToString("; ")}",
            )
            is PaperConfigLoadResult.Failed -> throw IllegalStateException(
                "Failed to load Emotify configuration",
                result.failure,
            )
        }

    private fun applyConfiguration(configuration: PaperRuntimeConfig): PaperConfigurationApplyResult {
        check(server.isPrimaryThread) { "Emotify configuration must be applied on the primary server thread" }
        if (configurationState.current() == configuration) {
            return PaperConfigurationApplyResult(changed = false, queuedPolicyRefreshes = 0)
        }
        return configurationApplyTransaction.execute(configuration)
    }

    private fun applyConfigurationUnsafe(configuration: PaperRuntimeConfig): PaperConfigurationApplyResult {
        globalSelectionBudget.reconfigure(configuration.ingress.globalSelectionLimits)
        ingressGate.reconfigure(configuration.ingress.maximumQueuedMainThreadTasks)
        val result = runtime.reconfigure(
            serverConfiguration(configuration),
            configuration.broadcast.budgetLimits,
        )
        val queuedPolicyRefreshes = result.refreshPlan?.let { plan ->
            policyRefreshDispatcher.replace(connections.activeConnections(), plan)
        } ?: 0
        return PaperConfigurationApplyResult(
            changed = true,
            queuedPolicyRefreshes = queuedPolicyRefreshes,
        )
    }

    private fun reportPolicyRefreshBatch(result: PaperPolicyRefreshBatchResult) {
        if (result.failedSessions == 0 || !diagnosticGate.tryAdmit()) {
            return
        }
        val failure = result.firstFailure
        if (failure == null) {
            logger.warning("Failed to refresh Emotify policy for ${result.failedSessions} client sessions")
        } else {
            logger.log(
                Level.SEVERE,
                "Failed to refresh Emotify policy for ${result.failedSessions} client sessions",
                failure,
            )
        }
    }

    private fun serverConfiguration(configuration: PaperRuntimeConfig): ServerRuntimeConfiguration =
        ServerRuntimeConfiguration(
            ServerHello(capabilities, configuration.cooldownMillis, configuration.allowedEmotions),
            ServerSelectionPolicy(
                enabled = configuration.enabled,
                catalog = BuiltInEmotionCatalog.catalog,
                allowedEmotions = configuration.allowedEmotions,
                customEmojisEnabled = configuration.customEmojisEnabled,
                maximumStaticCustomEmojiSize = configuration.maximumStaticCustomEmojiSize,
                maximumAnimatedCustomEmojiSize = configuration.maximumAnimatedCustomEmojiSize,
            ),
            configuration.broadcast.audience,
        )

    private companion object {
        const val MAX_SERVER_HELLO_ATTEMPTS = 3
        const val DIAGNOSTIC_BURST_CAPACITY = 8
        const val DIAGNOSTIC_REFILL_TOKENS_PER_SECOND = 2
    }
}
