package me.whish.emotify.server.core

import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmojiAssetChunk

internal val TEST_HAPPY = EmotionId.of("emotify:happy")
internal val TEST_LOVE = EmotionId.of("emotify:love")
internal val TEST_UNKNOWN = EmotionId.of("external:unknown")
internal val TEST_CATALOG = EmotionCatalog.of(listOf(TEST_HAPPY, TEST_LOVE))
internal val TEST_CAPABILITIES = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
internal val TEST_CLIENT_HELLO = ClientHello(TEST_CAPABILITIES)
internal val TEST_SERVER_HELLO = ServerHello(TEST_CAPABILITIES, 1_200, TEST_CATALOG)
internal val TEST_ENABLED_POLICY = ServerSelectionPolicy(true, TEST_CATALOG, TEST_CATALOG)

internal fun testConnection(
    index: Long,
    playerId: UUID = UUID(0L, index),
): ConnectionKey = ConnectionKey(playerId, ConnectionId.of(index))

internal fun testPlayer(
    connection: ConnectionKey,
    alive: Boolean = true,
    spectator: Boolean = false,
    invisible: Boolean = false,
    dimensionId: Int = 1,
    regionKey: Long = 1L,
): PlayerSnapshot = PlayerSnapshot(
    connection,
    RuntimeEntityId.of(connection.connectionId.value.toInt()),
    alive,
    spectator,
    invisible,
    dimensionId,
    regionKey,
)

internal data class RecordedPlay(
    val playerId: UUID,
    val connectionId: ConnectionId,
    val play: EmotionPlay,
)

internal data class RecordedCustomAsset(
    val playerId: UUID,
    val connectionId: ConnectionId,
    val transfer: CustomEmojiTransfer,
    val losslessChunks: List<CustomEmojiAssetChunk>?,
)
internal data class RecordedCustomPlay(val playerId: UUID, val connectionId: ConnectionId, val play: CustomEmotionPlay)

internal class RecordingOutboundTransport : OutboundTransport {
    var helloStatus = OutboundDeliveryStatus.SENT
    var rejectionStatus = OutboundDeliveryStatus.SENT
    var playResponder: (UUID, ConnectionId, EmotionPlay) -> OutboundDeliveryStatus = { _, _, _ ->
        OutboundDeliveryStatus.SENT
    }
    val hellos = mutableListOf<Pair<ConnectionKey, ServerHello>>()
    val rejections = mutableListOf<Pair<ConnectionKey, SelectionRejected>>()
    val plays = mutableListOf<RecordedPlay>()
    val customAssets = mutableListOf<RecordedCustomAsset>()
    val customPlays = mutableListOf<RecordedCustomPlay>()
    var preparedHelloCount = 0
    var preparedPlayCount = 0

    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        preparedHelloCount += 1
        return PreparedServerHelloDelivery { connection ->
            hellos += connection to hello
            helloStatus
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus {
        rejections += connection to rejection
        return rejectionStatus
    }

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery {
        preparedPlayCount += 1
        return PreparedEmotionDelivery { playerId, connectionId ->
            plays += RecordedPlay(playerId, connectionId, play)
            playResponder(playerId, connectionId, play)
        }
    }

    override fun prepareCustomEmojiAsset(transfer: CustomEmojiTransfer): PreparedCustomEmojiAssetDelivery =
        prepareCustomEmojiAsset(transfer, null)

    override fun prepareCustomEmojiAsset(
        transfer: CustomEmojiTransfer,
        losslessChunks: List<CustomEmojiAssetChunk>?,
    ): PreparedCustomEmojiAssetDelivery =
        PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
            customAssets += RecordedCustomAsset(playerId, connectionId, transfer, losslessChunks)
            OutboundDeliveryStatus.SENT
        }

    override fun prepareCustomEmotionPlay(play: CustomEmotionPlay): PreparedCustomEmotionDelivery =
        PreparedCustomEmotionDelivery { playerId, connectionId ->
            customPlays += RecordedCustomPlay(playerId, connectionId, play)
            OutboundDeliveryStatus.SENT
        }
}

internal class MutableAudiencePort : AudiencePort {
    var delegate: AudiencePort = AudiencePort { _, _, _ -> AudienceVisitCompletion.EXHAUSTED }

    override fun visitTracking(
        source: PlayerSnapshot,
        maxCandidates: Int,
        visitor: AudienceVisitor,
    ): AudienceVisitCompletion = delegate.visitTracking(source, maxCandidates, visitor)
}

internal data class AudienceCandidateFixture(
    val connection: ConnectionKey,
    val visible: Boolean = true,
    val sameDimension: Boolean = true,
    val distanceSquared: Double = 1.0,
)

internal fun candidateAudiencePort(
    candidates: List<AudienceCandidateFixture>,
    completion: AudienceVisitCompletion = AudienceVisitCompletion.EXHAUSTED,
): AudiencePort = AudiencePort { _, _, visitor ->
    for (candidate in candidates) {
        val shouldContinue = visitor.visit(
            candidate.connection.playerId,
            candidate.connection.connectionId,
            candidate.visible,
            candidate.sameDimension,
            candidate.distanceSquared,
        )
        if (!shouldContinue) {
            return@AudiencePort AudienceVisitCompletion.LIMIT_REACHED
        }
    }
    completion
}

internal data class EngineHarness(
    val time: FakeMonotonicTimeSource,
    val audiencePort: MutableAudiencePort,
    val transport: RecordingOutboundTransport,
    val audienceBudget: AudienceBudget,
    val sequence: ServerEventSequence,
    val ingressBudget: GlobalSelectionIngressBudget,
    val engine: EmotifyServerEngine,
)

internal fun engineHarness(
    time: FakeMonotonicTimeSource = FakeMonotonicTimeSource(),
    audiencePort: MutableAudiencePort = MutableAudiencePort(),
    transport: RecordingOutboundTransport = RecordingOutboundTransport(),
    audienceBudget: AudienceBudget = AudienceBudget(timeSource = time),
    sequence: ServerEventSequence = ServerEventSequence(),
    ingressBudget: GlobalSelectionIngressBudget = GlobalSelectionIngressBudget(timeSource = time),
    policy: ServerSelectionPolicy = TEST_ENABLED_POLICY,
    serverHello: ServerHello = TEST_SERVER_HELLO,
    featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
    customAssetIngressBudget: CustomAssetIngressBudget = CustomAssetIngressBudget(timeSource = time),
): EngineHarness {
    val engine = EmotifyServerEngine(
        serverHello,
        policy,
        time,
        audiencePort,
        transport,
        audienceBudget,
        sequence,
        ingressBudget,
        featureRegistry = featureRegistry,
        customAssetIngressBudget = customAssetIngressBudget,
    )
    return EngineHarness(time, audiencePort, transport, audienceBudget, sequence, ingressBudget, engine)
}

internal fun EngineHarness.openSupported(connection: ConnectionKey, hello: ClientHello) {
    engine.open(connection)
    engine.receiveClientHello(connection, hello)
}

internal fun EngineHarness.openSupported(connection: ConnectionKey) {
    engine.open(connection)
    engine.receiveClientHello(connection, TEST_CLIENT_HELLO)
}
