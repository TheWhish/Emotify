package me.whish.emotify.paper.runtime

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.server.core.AudiencePort
import me.whish.emotify.server.core.AudienceBudget
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.CustomAssetUploadCommit
import me.whish.emotify.server.core.CustomAssetUploadPreparation
import me.whish.emotify.server.core.CustomAssetUploadRejection
import me.whish.emotify.server.core.CustomAssetVerificationQueue
import me.whish.emotify.server.core.CustomAssetVerificationQueueEvent
import me.whish.emotify.server.core.EmotifyServerEngine
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.OutboundAttempt
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PlayerSnapshot
import me.whish.emotify.server.core.ServerClearResult
import me.whish.emotify.server.core.ServerCloseResult
import me.whish.emotify.server.core.ServerHelloResult
import me.whish.emotify.server.core.ServerConfigurationReplacement
import me.whish.emotify.server.core.ServerHelloRefreshPlan
import me.whish.emotify.server.core.ServerRuntimeConfiguration
import me.whish.emotify.server.core.ServerAudiencePolicy
import me.whish.emotify.server.core.ServerSelectionPolicy
import me.whish.emotify.server.core.ServerSelectionResult
import me.whish.emotify.wire.v1.ProtocolV1PortableProfile

sealed interface PaperServerOpenResult {
    data object Opened : PaperServerOpenResult

    data object AlreadyOpen : PaperServerOpenResult

    data class Undelivered(val outbound: OutboundAttempt) : PaperServerOpenResult
}

data class PaperServerReconfigurationResult(
    val replacement: ServerConfigurationReplacement,
    val refreshPlan: ServerHelloRefreshPlan?,
)

class PaperServerRuntime(
    serverHello: ServerHello,
    selectionPolicy: ServerSelectionPolicy,
    timeSource: MonotonicTimeSource,
    audiencePort: AudiencePort,
    outboundTransport: OutboundTransport,
    private val isMainThread: () -> Boolean,
    ingressBudget: GlobalSelectionIngressBudget = GlobalSelectionIngressBudget(timeSource = timeSource),
    audienceBudget: AudienceBudget = AudienceBudget(timeSource = timeSource),
    audiencePolicy: ServerAudiencePolicy = ServerAudiencePolicy.DEFAULT,
    featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
    private val playerSnapshotProvider: (ConnectionKey) -> PlayerSnapshot? = { null },
    private val resumedSelectionConsumer: (ConnectionKey, ServerSelectionResult) -> Unit = { _, _ -> },
) {
    private val portableServerHello = ProtocolV1PortableProfile.requireServerHello(serverHello)
    private val engine = EmotifyServerEngine(
        portableServerHello,
        selectionPolicy,
        timeSource,
        audiencePort,
        outboundTransport,
        audienceBudget = audienceBudget,
        ingressBudget = ingressBudget,
        audiencePolicy = audiencePolicy,
        featureRegistry = featureRegistry,
    )
    private val customAssetVerifications = CustomAssetVerificationQueue()

    val activeSessionCount: Int
        get() {
            requireMainThread()
            return engine.activeSessionCount
        }

    fun open(connection: ConnectionKey): PaperServerOpenResult {
        requireMainThread()
        if (engine.activeConnection(connection.playerId) == connection) {
            return PaperServerOpenResult.AlreadyOpen
        }
        val result = engine.open(connection)
        if (result.hello.status == OutboundDeliveryStatus.SENT) {
            return PaperServerOpenResult.Opened
        }
        engine.close(connection)
        return PaperServerOpenResult.Undelivered(result.hello)
    }

    fun receiveClientHello(connection: ConnectionKey, hello: ClientHello): ServerHelloResult {
        requireMainThread()
        return engine.receiveClientHello(connection, hello)
    }

    fun refresh(connection: ConnectionKey): OutboundAttempt {
        requireMainThread()
        return engine.refreshServerHello(connection)
    }

    fun select(player: PlayerSnapshot, selection: EmotionSelection): ServerSelectionResult {
        requireMainThread()
        return engine.select(player, selection.emotionId)
    }

    fun selectCustom(player: PlayerSnapshot, selection: CustomEmotionSelection): ServerSelectionResult {
        requireMainThread()
        return engine.selectCustom(player, selection)
    }

    fun enqueueCustomAssetChunk(
        connection: ConnectionKey,
        chunk: CustomEmojiAssetChunk,
        permittedToUpload: Boolean,
    ): CustomAssetUploadPreparation {
        requireMainThread()
        val preparation = engine.prepareCustomAssetChunk(connection, chunk, permittedToUpload)
        if (
            preparation is CustomAssetUploadPreparation.VerificationRequired &&
            !customAssetVerifications.trySubmit(preparation.task)
        ) {
            engine.cancelCustomAssetVerification(
                preparation.task,
                CustomAssetUploadRejection.QUEUE_SATURATED,
            )
            return CustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.QUEUE_SATURATED)
        }
        return preparation
    }

    fun drainCustomAssetVerifications(allowPlayerSnapshots: Boolean = true) {
        requireMainThread()
        while (true) {
            val event = customAssetVerifications.pollEvent() ?: return
            when (event) {
                is CustomAssetVerificationQueueEvent.Cancelled -> engine.cancelCustomAssetVerification(
                    event.task,
                    CustomAssetUploadRejection.QUEUE_SATURATED,
                )
                is CustomAssetVerificationQueueEvent.Completed -> {
                    val connection = event.completion.task.connection
                    val commit = engine.completeCustomAssetVerification(
                        event.completion,
                        if (allowPlayerSnapshots) playerSnapshotProvider(connection) else null,
                    )
                    resumedSelection(commit)?.let { result -> resumedSelectionConsumer(connection, result) }
                }
            }
        }
    }

    fun close(connection: ConnectionKey): ServerCloseResult {
        requireMainThread()
        return engine.close(connection)
    }

    fun reconfigure(
        configuration: ServerRuntimeConfiguration,
        audienceLimits: AudienceBudgetLimits,
    ): PaperServerReconfigurationResult {
        requireMainThread()
        engine.reconfigureAudienceBudget(audienceLimits)
        val replacement = engine.replaceConfiguration(configuration)
        val refreshPlan = if (replacement.previous.serverHello != replacement.current.serverHello) {
            engine.prepareServerHelloRefresh()
        } else {
            null
        }
        return PaperServerReconfigurationResult(replacement, refreshPlan)
    }

    fun clear(): ServerClearResult {
        requireMainThread()
        customAssetVerifications.close()
        drainCustomAssetVerifications(allowPlayerSnapshots = false)
        return engine.clear()
    }

    private fun requireMainThread() {
        check(isMainThread()) { "Paper server state must be accessed on the primary server thread" }
    }

    private fun resumedSelection(commit: CustomAssetUploadCommit): ServerSelectionResult? = when (commit) {
        is CustomAssetUploadCommit.Accepted -> commit.resumedSelection
        is CustomAssetUploadCommit.Rejected -> commit.resumedSelection
        CustomAssetUploadCommit.Stale -> null
    }
}
