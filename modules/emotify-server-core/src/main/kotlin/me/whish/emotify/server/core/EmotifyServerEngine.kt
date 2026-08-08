package me.whish.emotify.server.core

import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.wire.v1.ProtocolV1Codecs

enum class OutboundDeliveryStatus {
    SENT,
    FAILED,
    UNAVAILABLE,
}

data class OutboundAttempt(
    val status: OutboundDeliveryStatus,
    val failure: RuntimeException? = null,
) {
    init {
        require(failure == null || status == OutboundDeliveryStatus.FAILED) {
            "Outbound failures require FAILED delivery status"
        }
    }
}

interface OutboundTransport {
    fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery

    fun sendSelectionRejected(connection: ConnectionKey, rejection: SelectionRejected): OutboundDeliveryStatus

    fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery

    fun prepareCustomEmojiAsset(transfer: CustomEmojiTransfer): PreparedCustomEmojiAssetDelivery =
        PreparedCustomEmojiAssetDelivery { _, _ -> OutboundDeliveryStatus.UNAVAILABLE }

    fun prepareCustomEmojiAsset(
        transfer: CustomEmojiTransfer,
        losslessChunks: List<CustomEmojiAssetChunk>?,
    ): PreparedCustomEmojiAssetDelivery = prepareCustomEmojiAsset(transfer)

    fun prepareCustomEmotionPlay(play: CustomEmotionPlay): PreparedCustomEmotionDelivery =
        PreparedCustomEmotionDelivery { _, _ -> OutboundDeliveryStatus.UNAVAILABLE }
}

fun interface PreparedServerHelloDelivery {
    fun send(connection: ConnectionKey): OutboundDeliveryStatus
}

fun interface PreparedEmotionDelivery {
    fun send(playerId: UUID, connectionId: ConnectionId): OutboundDeliveryStatus
}

fun interface PreparedCustomEmojiAssetDelivery {
    fun send(playerId: UUID, connectionId: ConnectionId): OutboundDeliveryStatus
}

fun interface PreparedCustomEmotionDelivery {
    fun send(playerId: UUID, connectionId: ConnectionId): OutboundDeliveryStatus
}

data class ServerOpenResult(
    val connection: ConnectionKey,
    val hello: OutboundAttempt,
)

sealed interface ServerHelloResult {
    data object StaleConnection : ServerHelloResult

    data class Processed(
        val transition: ServerHandshakeTransition,
        val state: ServerHandshakeState,
    ) : ServerHelloResult
}

enum class ServerCloseResult {
    CLOSED,
    STALE_OR_MISSING,
}

data class ServerClearResult(
    val closedSessions: Int,
)

data class ServerPolicyReplacement(
    val previous: ServerSelectionPolicy,
    val current: ServerSelectionPolicy,
)

data class ServerRuntimeConfiguration(
    val serverHello: ServerHello,
    val selectionPolicy: ServerSelectionPolicy,
    val audiencePolicy: ServerAudiencePolicy = ServerAudiencePolicy.DEFAULT,
) {
    init {
        require(selectionPolicy.allowedEmotions == serverHello.emotionCatalog) {
            "Advertised emotions must match the allowed emotion policy"
        }
    }
}

data class ServerConfigurationReplacement(
    val previous: ServerRuntimeConfiguration,
    val current: ServerRuntimeConfiguration,
)

data class ServerHelloRefreshResult(
    val attemptedSessions: Int,
    val sentSessions: Int,
    val unavailableSessions: Int,
    val failedSessions: Int,
    val firstFailure: RuntimeException?,
)

class ServerHelloRefreshPlan internal constructor(
    private val sessions: ServerSessionRegistry,
    private val delivery: PreparedServerHelloDelivery,
) {
    fun send(connection: ConnectionKey): OutboundAttempt {
        if (!sessions.isRefreshable(connection)) {
            return OutboundAttempt(OutboundDeliveryStatus.UNAVAILABLE)
        }
        return try {
            OutboundAttempt(delivery.send(connection))
        } catch (exception: RuntimeException) {
            OutboundAttempt(OutboundDeliveryStatus.FAILED, exception)
        }
    }
}

enum class SelectionIgnoreReason {
    STALE_CONNECTION,
    HANDSHAKE_INCOMPLETE,
    UNKNOWN_EMOTION,
}

sealed interface RejectionDispatch {
    data object RateLimited : RejectionDispatch

    data class Attempted(val outbound: OutboundAttempt) : RejectionDispatch
}

sealed interface AudienceTraversalOutcome {
    data class Completed(val completion: AudienceVisitCompletion) : AudienceTraversalOutcome

    data class Failed(val failure: RuntimeException) : AudienceTraversalOutcome
}

sealed interface ServerSelectionResult {
    data class Ignored(val reason: SelectionIgnoreReason) : ServerSelectionResult

    data class Rejected(
        val reason: SelectionRejectionReason,
        val retryAfterMillis: Int,
        val dispatch: RejectionDispatch,
    ) : ServerSelectionResult

    data class Published(
        val play: EmotionPlay,
        val deliveredRecipients: Int,
        val failedRecipients: Int,
        val throttledRecipients: Int,
        val visitedCandidates: Int,
        val traversal: AudienceTraversalOutcome,
        val firstSendFailure: RuntimeException?,
    ) : ServerSelectionResult

    data class Undelivered(
        val play: EmotionPlay,
        val failedRecipients: Int,
        val throttledRecipients: Int,
        val visitedCandidates: Int,
        val traversal: AudienceTraversalOutcome,
        val firstSendFailure: RuntimeException?,
    ) : ServerSelectionResult
}

class EmotifyServerEngine(
    serverHello: ServerHello,
    selectionPolicy: ServerSelectionPolicy,
    timeSource: MonotonicTimeSource,
    private val audiencePort: AudiencePort,
    private val outboundTransport: OutboundTransport,
    private val audienceBudget: AudienceBudget = AudienceBudget(timeSource = timeSource),
    private val eventSequence: ServerEventSequence = ServerEventSequence(),
    val ingressBudget: GlobalSelectionIngressBudget = GlobalSelectionIngressBudget(timeSource = timeSource),
    featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
    audiencePolicy: ServerAudiencePolicy = ServerAudiencePolicy.DEFAULT,
    private val customAssets: ServerCustomAssetStore = ServerCustomAssetStore(),
    private val customAssetIngressBudget: CustomAssetIngressBudget = CustomAssetIngressBudget(timeSource = timeSource),
    private val customAssetEgressBudget: CustomAssetEgressBudget = CustomAssetEgressBudget(timeSource = timeSource),
) {
    private var configuration = ServerRuntimeConfiguration(serverHello, selectionPolicy, audiencePolicy)

    private val sessions = ServerSessionRegistry(
        configuration.serverHello.capabilities,
        configuration.serverHello.cooldownMillis.milliseconds,
        timeSource,
        featureRegistry,
        customAssets,
        customAssetIngressBudget,
    )

    val activeSessionCount: Int
        get() = sessions.size

    fun activeConnection(playerId: UUID): ConnectionKey? = sessions.activeConnection(playerId)

    fun open(connection: ConnectionKey): ServerOpenResult {
        sessions.open(connection)
        return ServerOpenResult(
            connection,
            try {
                prepareServerHelloRefresh().send(connection)
            } catch (exception: RuntimeException) {
                OutboundAttempt(OutboundDeliveryStatus.FAILED, exception)
            },
        )
    }

    fun receiveClientHello(connection: ConnectionKey, hello: ClientHello): ServerHelloResult {
        val session = sessions.get(connection) ?: return ServerHelloResult.StaleConnection
        val transition = session.receiveClientHello(hello)
        return ServerHelloResult.Processed(transition, session.handshakeState)
    }

    fun select(player: PlayerSnapshot, emotionId: EmotionId): ServerSelectionResult {
        val session = sessions.get(player.connection)
            ?: return ServerSelectionResult.Ignored(SelectionIgnoreReason.STALE_CONNECTION)
        if (session.handshakeState !is ServerHandshakeState.Supported) {
            return ServerSelectionResult.Ignored(SelectionIgnoreReason.HANDSHAKE_INCOMPLETE)
        }
        val activeConfiguration = configuration
        if (!activeConfiguration.selectionPolicy.catalog.contains(emotionId)) {
            return ServerSelectionResult.Ignored(SelectionIgnoreReason.UNKNOWN_EMOTION)
        }

        return when (val preparation = session.prepareSelection(emotionId, activeConfiguration.selectionPolicy, player)) {
            SelectionPreparation.Ready -> publish(player, session, emotionId)
            SelectionPreparation.Ignored -> ServerSelectionResult.Ignored(SelectionIgnoreReason.UNKNOWN_EMOTION)
            is SelectionPreparation.Rejected -> reject(player.connection, session, preparation)
        }
    }

    fun selectCustom(player: PlayerSnapshot, selection: CustomEmotionSelection): ServerSelectionResult {
        val session = sessions.get(player.connection)
            ?: return ServerSelectionResult.Ignored(SelectionIgnoreReason.STALE_CONNECTION)
        if (session.handshakeState !is ServerHandshakeState.Supported) {
            return ServerSelectionResult.Ignored(SelectionIgnoreReason.HANDSHAKE_INCOMPLETE)
        }

        return when (val preparation = session.prepareCustomSelection(selection, configuration.selectionPolicy, player)) {
            is CustomSelectionPreparation.Ready -> publishCustom(
                player,
                session,
                preparation.asset,
                preparation.losslessChunks,
            )
            CustomSelectionPreparation.Ignored -> ServerSelectionResult.Ignored(SelectionIgnoreReason.UNKNOWN_EMOTION)
            is CustomSelectionPreparation.Rejected -> reject(
                player.connection,
                session,
                SelectionPreparation.Rejected(preparation.reason, preparation.retryAfterMillis),
            )
        }
    }

    fun receiveCustomAssetChunk(connection: ConnectionKey, chunk: CustomEmojiAssetChunk): Boolean =
        sessions.get(connection)?.receiveCustomAssetChunk(
            chunk,
            configuration.selectionPolicy,
        ) == true

    fun close(connection: ConnectionKey): ServerCloseResult =
        if (sessions.close(connection)) ServerCloseResult.CLOSED else ServerCloseResult.STALE_OR_MISSING

    fun replacePolicy(newPolicy: ServerSelectionPolicy): ServerPolicyReplacement {
        require(newPolicy.catalog == configuration.selectionPolicy.catalog) {
            "Replacement policy catalog must match the active server catalog"
        }
        val previous = configuration.selectionPolicy
        configuration = configuration.copy(
            serverHello = configuration.serverHello.copy(emotionCatalog = newPolicy.allowedEmotions),
            selectionPolicy = newPolicy,
        )
        sessions.clearCustomAssetRejections()
        return ServerPolicyReplacement(previous, newPolicy)
    }

    fun replaceConfiguration(newConfiguration: ServerRuntimeConfiguration): ServerConfigurationReplacement {
        val previous = configuration
        require(newConfiguration.serverHello.capabilities == previous.serverHello.capabilities) {
            "Server capabilities cannot change while sessions are active"
        }
        require(newConfiguration.selectionPolicy.catalog == previous.selectionPolicy.catalog) {
            "Server catalog cannot change while sessions are active"
        }
        sessions.reconfigureSelectionCooldown(newConfiguration.serverHello.cooldownMillis.milliseconds)
        sessions.clearCustomAssetRejections()
        configuration = newConfiguration
        return ServerConfigurationReplacement(previous, newConfiguration)
    }

    fun refreshServerHello(): ServerHelloRefreshResult {
        val refreshableSessions = sessions.refreshableCount
        val plan = try {
            prepareServerHelloRefresh()
        } catch (exception: RuntimeException) {
            return ServerHelloRefreshResult(
                refreshableSessions,
                sentSessions = 0,
                unavailableSessions = 0,
                failedSessions = refreshableSessions,
                firstFailure = exception,
            )
        }
        var attempted = 0
        var sent = 0
        var unavailable = 0
        var failed = 0
        var firstFailure: RuntimeException? = null
        sessions.visitRefreshable { connection ->
            attempted += 1
            val outbound = plan.send(connection)
            when (outbound.status) {
                OutboundDeliveryStatus.SENT -> sent += 1
                OutboundDeliveryStatus.UNAVAILABLE -> unavailable += 1
                OutboundDeliveryStatus.FAILED -> {
                    failed += 1
                    if (firstFailure == null) {
                        firstFailure = outbound.failure
                    }
                }
            }
        }
        return ServerHelloRefreshResult(attempted, sent, unavailable, failed, firstFailure)
    }

    fun refreshServerHello(connection: ConnectionKey): OutboundAttempt {
        return try {
            prepareServerHelloRefresh().send(connection)
        } catch (exception: RuntimeException) {
            OutboundAttempt(OutboundDeliveryStatus.FAILED, exception)
        }
    }

    fun prepareServerHelloRefresh(): ServerHelloRefreshPlan = ServerHelloRefreshPlan(
        sessions,
        outboundTransport.prepareServerHello(configuration.serverHello),
    )

    fun reconfigureAudienceBudget(limits: AudienceBudgetLimits) {
        audienceBudget.reconfigure(limits)
    }

    fun clear(): ServerClearResult {
        val closedSessions = sessions.clear()
        audienceBudget.clear()
        eventSequence.reset()
        ingressBudget.reset()
        customAssets.clear()
        customAssetIngressBudget.reset()
        customAssetEgressBudget.reset()
        return ServerClearResult(closedSessions)
    }

    private fun publish(
        player: PlayerSnapshot,
        session: ServerPlayerSession,
        emotionId: EmotionId,
    ): ServerSelectionResult {
        if (!eventSequence.hasCapacity()) {
            return reject(
                player.connection,
                session,
                SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0),
            )
        }

        when (audienceBudget.tryReserve(player.dimensionId, player.regionKey)) {
            AudienceReservation.GLOBAL_BUSY,
            AudienceReservation.REGION_BUSY,
            -> return reject(
                player.connection,
                session,
                SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0),
            )
            AudienceReservation.RESERVED -> Unit
        }

        val sequence = checkNotNull(eventSequence.nextOrNull()) {
            "Event sequence exhausted after a successful capacity check"
        }
        val play = EmotionPlay(player.entityId, player.connection.playerId, sequence, emotionId)
        val preparedDelivery = try {
            outboundTransport.prepareEmotionPlay(play)
        } catch (exception: RuntimeException) {
            audienceBudget.refund(player.dimensionId, player.regionKey)
            return ServerSelectionResult.Undelivered(
                play,
                failedRecipients = 1,
                throttledRecipients = 0,
                visitedCandidates = 0,
                traversal = AudienceTraversalOutcome.Failed(exception),
                firstSendFailure = exception,
            )
        }
        val delivery = DeliveryAccumulator(preparedDelivery)
        val traversal = try {
            delivery.deliver(
                player.connection.playerId,
                player.connection.connectionId,
                session,
                self = true,
            )
            val completion = audiencePort.visitTracking(
                player,
                configuration.audiencePolicy.maximumTrackingCandidates,
                delivery,
            )
            AudienceTraversalOutcome.Completed(delivery.normalize(completion))
        } catch (exception: RuntimeException) {
            AudienceTraversalOutcome.Failed(exception)
        } catch (error: Error) {
            if (delivery.deliveredRecipients == 0) {
                audienceBudget.refund(player.dimensionId, player.regionKey)
            } else {
                session.commitSelection()
            }
            throw error
        }

        if (delivery.deliveredRecipients == 0) {
            audienceBudget.refund(player.dimensionId, player.regionKey)
            return ServerSelectionResult.Undelivered(
                play,
                delivery.failedRecipients,
                delivery.throttledRecipients,
                delivery.visitedCandidates,
                traversal,
                delivery.firstSendFailure,
            )
        }

        session.commitSelection()
        return ServerSelectionResult.Published(
            play,
            delivery.deliveredRecipients,
            delivery.failedRecipients,
            delivery.throttledRecipients,
            delivery.visitedCandidates,
            traversal,
            delivery.firstSendFailure,
        )
    }

    private fun publishCustom(
        player: PlayerSnapshot,
        session: ServerPlayerSession,
        asset: CustomEmojiAsset,
        losslessChunks: List<CustomEmojiAssetChunk>?,
    ): ServerSelectionResult {
        if (!eventSequence.hasCapacity()) {
            return reject(
                player.connection,
                session,
                SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0),
            )
        }

        when (audienceBudget.tryReserve(player.dimensionId, player.regionKey)) {
            AudienceReservation.GLOBAL_BUSY,
            AudienceReservation.REGION_BUSY,
            -> return reject(
                player.connection,
                session,
                SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0),
            )
            AudienceReservation.RESERVED -> Unit
        }

        val sequence = checkNotNull(eventSequence.nextOrNull()) {
            "Event sequence exhausted after a successful capacity check"
        }
        val play = EmotionPlay(player.entityId, player.connection.playerId, sequence, asset.id.emotionId)
        val customPlay = CustomEmotionPlay(player.entityId, player.connection.playerId, sequence, asset.id)
        val preparedAsset: PreparedCustomEmojiAssetDelivery
        val preparedPlay: PreparedCustomEmotionDelivery
        try {
            preparedAsset = outboundTransport.prepareCustomEmojiAsset(CustomEmojiTransfer(asset), losslessChunks)
            preparedPlay = outboundTransport.prepareCustomEmotionPlay(customPlay)
        } catch (exception: RuntimeException) {
            audienceBudget.refund(player.dimensionId, player.regionKey)
            return ServerSelectionResult.Undelivered(
                play,
                failedRecipients = 1,
                throttledRecipients = 0,
                visitedCandidates = 0,
                traversal = AudienceTraversalOutcome.Failed(exception),
                firstSendFailure = exception,
            )
        }
        val delivery = CustomDeliveryAccumulator(
            preparedAsset,
            preparedPlay,
            asset,
            losslessChunks?.size ?: 1,
            losslessChunks?.sumOf(ProtocolV1Codecs.customAssetChunk::encodedSize)
                ?: (asset.rawByteLength + LEGACY_CUSTOM_ASSET_MAXIMUM_OVERHEAD_BYTES),
        )
        val traversal = try {
            delivery.deliver(
                player.connection.playerId,
                player.connection.connectionId,
                session,
                self = true,
            )
            val completion = audiencePort.visitTracking(
                player,
                configuration.audiencePolicy.maximumTrackingCandidates,
                delivery,
            )
            AudienceTraversalOutcome.Completed(delivery.normalize(completion))
        } catch (exception: RuntimeException) {
            AudienceTraversalOutcome.Failed(exception)
        } catch (error: Error) {
            if (delivery.deliveredRecipients == 0) {
                audienceBudget.refund(player.dimensionId, player.regionKey)
            } else {
                session.commitSelection()
            }
            throw error
        }

        if (delivery.deliveredRecipients == 0) {
            audienceBudget.refund(player.dimensionId, player.regionKey)
            return ServerSelectionResult.Undelivered(
                play,
                delivery.failedRecipients,
                delivery.throttledRecipients,
                delivery.visitedCandidates,
                traversal,
                delivery.firstSendFailure,
            )
        }

        session.commitSelection()
        return ServerSelectionResult.Published(
            play,
            delivery.deliveredRecipients,
            delivery.failedRecipients,
            delivery.throttledRecipients,
            delivery.visitedCandidates,
            traversal,
            delivery.firstSendFailure,
        )
    }

    private fun reject(
        connection: ConnectionKey,
        session: ServerPlayerSession,
        rejection: SelectionPreparation.Rejected,
    ): ServerSelectionResult.Rejected {
        if (!session.tryAdmitRejection()) {
            return ServerSelectionResult.Rejected(
                rejection.reason,
                rejection.retryAfterMillis,
                RejectionDispatch.RateLimited,
            )
        }

        val message = SelectionRejected(
            SelectionRejectionCode.from(rejection.reason),
            rejection.retryAfterMillis,
        )
        return ServerSelectionResult.Rejected(
            rejection.reason,
            rejection.retryAfterMillis,
            RejectionDispatch.Attempted(
                attempt { outboundTransport.sendSelectionRejected(connection, message) },
            ),
        )
    }

    private fun attempt(send: () -> OutboundDeliveryStatus): OutboundAttempt =
        try {
            OutboundAttempt(send())
        } catch (exception: RuntimeException) {
            OutboundAttempt(OutboundDeliveryStatus.FAILED, exception)
        }

    private inner class DeliveryAccumulator(
        private val preparedDelivery: PreparedEmotionDelivery,
    ) : AudienceVisitor {
        var deliveredRecipients = 0
            private set
        var failedRecipients = 0
            private set
        var throttledRecipients = 0
            private set
        var visitedCandidates = 0
            private set
        var firstSendFailure: RuntimeException? = null
            private set
        private var limitReached = false

        override fun visit(
            playerId: UUID,
            connectionId: ConnectionId,
            visible: Boolean,
            sameDimension: Boolean,
            distanceSquared: Double,
        ): Boolean {
            val audiencePolicy = configuration.audiencePolicy
            if (visitedCandidates >= audiencePolicy.maximumTrackingCandidates) {
                limitReached = true
                return false
            }
            visitedCandidates += 1
            if (visitedCandidates == audiencePolicy.maximumTrackingCandidates) {
                limitReached = true
            }

            val recipientSession = sessions.get(playerId, connectionId)
            val negotiated = recipientSession?.handshakeState is ServerHandshakeState.Supported
            if (recipientSession != null && AudiencePolicy.isEligible(
                    audiencePolicy,
                    tracking = true,
                    negotiated = negotiated,
                    visible = visible,
                    sameDimension = sameDimension,
                    distanceSquared = distanceSquared,
                )
            ) {
                deliver(playerId, connectionId, recipientSession, self = false)
            }
            return !limitReached
        }

        fun deliver(
            playerId: UUID,
            connectionId: ConnectionId,
            session: ServerPlayerSession,
            self: Boolean,
        ) {
            if (!session.tryAdmitPlay(self)) {
                throttledRecipients += 1
                return
            }

            val outbound = try {
                attempt { preparedDelivery.send(playerId, connectionId) }
            } catch (error: Error) {
                session.refundPlay()
                throw error
            }
            if (outbound.status == OutboundDeliveryStatus.SENT) {
                deliveredRecipients += 1
                return
            }

            session.refundPlay()
            failedRecipients += 1
            if (firstSendFailure == null) {
                firstSendFailure = outbound.failure
            }
        }

        fun normalize(completion: AudienceVisitCompletion): AudienceVisitCompletion =
            if (limitReached) AudienceVisitCompletion.LIMIT_REACHED else completion
    }

    private inner class CustomDeliveryAccumulator(
        private val preparedAsset: PreparedCustomEmojiAssetDelivery,
        private val preparedPlay: PreparedCustomEmotionDelivery,
        private val asset: CustomEmojiAsset,
        private val assetTransferUnits: Int,
        private val assetTransferBytes: Int,
    ) : AudienceVisitor {
        var deliveredRecipients = 0
            private set
        var failedRecipients = 0
            private set
        var throttledRecipients = 0
            private set
        var visitedCandidates = 0
            private set
        var firstSendFailure: RuntimeException? = null
            private set
        private var limitReached = false

        override fun visit(
            playerId: UUID,
            connectionId: ConnectionId,
            visible: Boolean,
            sameDimension: Boolean,
            distanceSquared: Double,
        ): Boolean {
            val audiencePolicy = configuration.audiencePolicy
            if (visitedCandidates >= audiencePolicy.maximumTrackingCandidates) {
                limitReached = true
                return false
            }
            visitedCandidates += 1
            if (visitedCandidates == audiencePolicy.maximumTrackingCandidates) {
                limitReached = true
            }

            val recipientSession = sessions.get(playerId, connectionId)
            val negotiated = recipientSession?.supportsCustomEmojiAsset(asset) == true
            if (recipientSession != null && AudiencePolicy.isEligible(
                    audiencePolicy,
                    tracking = true,
                    negotiated = negotiated,
                    visible = visible,
                    sameDimension = sameDimension,
                    distanceSquared = distanceSquared,
                )
            ) {
                deliver(playerId, connectionId, recipientSession, self = false)
            }
            return !limitReached
        }

        fun deliver(
            playerId: UUID,
            connectionId: ConnectionId,
            session: ServerPlayerSession,
            self: Boolean,
        ) {
            if (!session.tryAdmitPlay(self)) {
                throttledRecipients += 1
                return
            }

            if (!self && session.needsCustomAsset(asset.id)) {
                if (!session.tryAdmitCustomAssetTransfer(assetTransferUnits)) {
                    session.refundPlay()
                    throttledRecipients += 1
                    return
                }
                if (!customAssetEgressBudget.tryReserve(assetTransferBytes)) {
                    session.refundCustomAssetTransfer(assetTransferUnits)
                    session.refundPlay()
                    throttledRecipients += 1
                    return
                }
                val assetOutbound = try {
                    attempt { preparedAsset.send(playerId, connectionId) }
                } catch (error: Error) {
                    session.refundPlay()
                    throw error
                }
                if (assetOutbound.status != OutboundDeliveryStatus.SENT) {
                    session.refundPlay()
                    recordFailure(assetOutbound)
                    return
                }
                session.markCustomAssetDelivered(asset)
            }

            val playOutbound = try {
                attempt { preparedPlay.send(playerId, connectionId) }
            } catch (error: Error) {
                session.refundPlay()
                throw error
            }
            if (playOutbound.status == OutboundDeliveryStatus.SENT) {
                deliveredRecipients += 1
                return
            }

            session.refundPlay()
            recordFailure(playOutbound)
        }

        private fun recordFailure(outbound: OutboundAttempt) {
            failedRecipients += 1
            if (firstSendFailure == null) {
                firstSendFailure = outbound.failure
            }
        }

        fun normalize(completion: AudienceVisitCompletion): AudienceVisitCompletion =
            if (limitReached) AudienceVisitCompletion.LIMIT_REACHED else completion
    }

    private companion object {
        const val LEGACY_CUSTOM_ASSET_MAXIMUM_OVERHEAD_BYTES = 90
    }
}
