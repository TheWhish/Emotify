package me.whish.emotify.paper.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.AudiencePort
import me.whish.emotify.server.core.AudienceBudgetLimits
import me.whish.emotify.server.core.AudienceVisitCompletion
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.CustomAssetUploadPreparation
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PlayerSnapshot
import me.whish.emotify.server.core.PreparedEmotionDelivery
import me.whish.emotify.server.core.PreparedServerHelloDelivery
import me.whish.emotify.server.core.ServerCloseResult
import me.whish.emotify.server.core.ServerHandshakeTransition
import me.whish.emotify.server.core.ServerHelloResult
import me.whish.emotify.server.core.ServerSelectionPolicy
import me.whish.emotify.server.core.ServerSelectionResult
import me.whish.emotify.server.core.SelectionIgnoreReason
import me.whish.emotify.server.core.ServerRuntimeConfiguration
import me.whish.emotify.wire.v1.WireEncodeException
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker

@Suppress("unused")
class PaperServerRuntimeTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val emotionId = EmotionId.of("emotify:happy")
    val catalog = EmotionCatalog.of(listOf(emotionId))
    val serverHello = ServerHello(capabilities, 3_000, catalog)
    val policy = ServerSelectionPolicy(true, catalog, catalog)

    test("open is idempotent and an unavailable hello leaves no session") {
        val transport = RecordingPaperOutboundTransport().apply {
            helloStatus = OutboundDeliveryStatus.UNAVAILABLE
        }
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            transport,
            { true },
        )
        val connection = connection(UUID.randomUUID(), 1)

        runtime.open(connection)
            .shouldBeInstanceOf<PaperServerOpenResult.Undelivered>()
            .outbound.status shouldBe OutboundDeliveryStatus.UNAVAILABLE
        runtime.activeSessionCount shouldBe 0

        transport.helloStatus = OutboundDeliveryStatus.SENT
        runtime.open(connection) shouldBe PaperServerOpenResult.Opened
        repeat(10_000) {
            runtime.open(connection) shouldBe PaperServerOpenResult.AlreadyOpen
        }

        runtime.activeSessionCount shouldBe 1
        transport.hellos.size shouldBe 2
    }

    test("failed hello is captured and rolled back before a clean retry") {
        val failure = IllegalStateException("synthetic send failure")
        val transport = RecordingPaperOutboundTransport().apply {
            helloFailure = failure
        }
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            transport,
            { true },
        )
        val connection = connection(UUID.randomUUID(), 1)

        val failed = runtime.open(connection).shouldBeInstanceOf<PaperServerOpenResult.Undelivered>()
        failed.outbound.status shouldBe OutboundDeliveryStatus.FAILED
        failed.outbound.failure shouldBe failure
        runtime.activeSessionCount shouldBe 0

        transport.helloFailure = null
        runtime.open(connection) shouldBe PaperServerOpenResult.Opened
        runtime.activeSessionCount shouldBe 1
    }

    test("runtime rejects a server hello outside the portable plugin messaging profile") {
        val oversizedCatalog = EmotionCatalog.of(
            (0 until 100).map { index ->
                EmotionId.of("emotify:${index.toString().padStart(3, '0')}${"x".repeat(48)}")
            },
        )

        shouldThrow<WireEncodeException> {
            PaperServerRuntime(
                ServerHello(capabilities, 3_000, oversizedCatalog),
                ServerSelectionPolicy(true, oversizedCatalog, oversizedCatalog),
                FakeMonotonicTimeSource(),
                { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
                RecordingPaperOutboundTransport(),
                { true },
            )
        }
    }

    test("supported selection publishes identical play to source and tracked recipient") {
        val source = connection(UUID.randomUUID(), 1)
        val recipient = connection(UUID.randomUUID(), 2)
        val transport = RecordingPaperOutboundTransport()
        val audience: AudiencePort = { _, _, visitor ->
            visitor.visit(recipient.playerId, recipient.connectionId, true, true, 25.0)
            AudienceVisitCompletion.EXHAUSTED
        }
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            audience,
            transport,
            { true },
        )
        runtime.open(source)
        runtime.open(recipient)
        runtime.receiveClientHello(source, ClientHello(capabilities))
            .shouldBeInstanceOf<ServerHelloResult.Processed>()
            .transition shouldBe ServerHandshakeTransition.SUPPORTED
        runtime.receiveClientHello(recipient, ClientHello(capabilities))
            .shouldBeInstanceOf<ServerHelloResult.Processed>()

        val result = runtime.select(
            PlayerSnapshot(
                source,
                RuntimeEntityId.of(41),
                alive = true,
                spectator = false,
                invisible = false,
                dimensionId = 1,
                regionKey = 2,
            ),
            EmotionSelection(emotionId),
        ).shouldBeInstanceOf<ServerSelectionResult.Published>()

        result.deliveredRecipients shouldBe 2
        transport.plays.map(RecordedPaperPlay::connection) shouldBe listOf(source, recipient)
        transport.plays.map(RecordedPaperPlay::play).distinct().size shouldBe 1
        transport.plays.single { it.connection == recipient }.play.sourceUuid shouldBe source.playerId
    }

    test("stale connection generations cannot mutate or close the replacement session") {
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            RecordingPaperOutboundTransport(),
            { true },
        )
        val playerId = UUID.randomUUID()
        val first = connection(playerId, 1)
        val second = connection(playerId, 2)
        runtime.open(first)
        runtime.open(second)

        runtime.receiveClientHello(first, ClientHello(capabilities)) shouldBe ServerHelloResult.StaleConnection
        runtime.close(first) shouldBe ServerCloseResult.STALE_OR_MISSING
        runtime.activeSessionCount shouldBe 1
        runtime.receiveClientHello(second, ClientHello(capabilities))
            .shouldBeInstanceOf<ServerHelloResult.Processed>()
    }

    test("server-only changes skip network refresh while client policy changes create one plan") {
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            RecordingPaperOutboundTransport(),
            { true },
        )
        val limits = AudienceBudgetLimits(512, 256, 32, 16, 4_096)
        val serverOnly = runtime.reconfigure(
            ServerRuntimeConfiguration(serverHello, policy.copy(enabled = false)),
            limits,
        )
        val clientPolicy = runtime.reconfigure(
            ServerRuntimeConfiguration(serverHello.copy(cooldownMillis = 4_000), policy),
            limits,
        )

        serverOnly.refreshPlan shouldBe null
        (clientPolicy.refreshPlan != null) shouldBe true
    }

    test("lossless custom asset verification resumes selection through the bounded worker") {
        val customCapabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val customHello = ServerHello(customCapabilities, 3_000, catalog)
        val source = connection(UUID.randomUUID(), 1)
        val snapshot = PlayerSnapshot(
            source,
            RuntimeEntityId.of(1),
            alive = true,
            spectator = false,
            invisible = false,
            dimensionId = 1,
            regionKey = 1,
        )
        val resumed = ArrayList<ServerSelectionResult>()
        val runtime = PaperServerRuntime(
            customHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            RecordingPaperOutboundTransport(),
            { true },
            featureRegistry = EmotifyProtocolFeatures.registry,
            playerSnapshotProvider = { connection -> snapshot.takeIf { it.connection == connection } },
            resumedSelectionConsumer = { _, result -> resumed += result },
        )
        val asset = CustomEmojiAsset.create(
            CustomEmojiPixels.of(128, IntArray(128 * 128) { index -> 0xFF000000.toInt() or index }),
        )

        try {
            runtime.open(source) shouldBe PaperServerOpenResult.Opened
            runtime.receiveClientHello(source, ClientHello(customCapabilities))
                .shouldBeInstanceOf<ServerHelloResult.Processed>()
            val chunks = CustomEmojiAssetChunker.split(asset)
            chunks.dropLast(1).forEach { chunk ->
                runtime.enqueueCustomAssetChunk(source, chunk, permittedToUpload = true) shouldBe
                    CustomAssetUploadPreparation.Pending
            }
            runtime.enqueueCustomAssetChunk(source, chunks.last(), permittedToUpload = true)
                .shouldBeInstanceOf<CustomAssetUploadPreparation.VerificationRequired>()
            runtime.selectCustom(snapshot, CustomEmotionSelection(asset.id, null)) shouldBe
                ServerSelectionResult.Ignored(SelectionIgnoreReason.CUSTOM_ASSET_VERIFYING)

            val deadline = System.nanoTime() + 5_000_000_000L
            while (resumed.isEmpty() && System.nanoTime() < deadline) {
                runtime.drainCustomAssetVerifications()
                Thread.onSpinWait()
            }

            resumed.single().shouldBeInstanceOf<ServerSelectionResult.Undelivered>()
        } finally {
            runtime.clear()
        }
    }

    test("every runtime state operation requires the primary server thread") {
        val runtime = PaperServerRuntime(
            serverHello,
            policy,
            FakeMonotonicTimeSource(),
            { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
            RecordingPaperOutboundTransport(),
            { false },
        )
        val connection = connection(UUID.randomUUID(), 1)
        val selection = EmotionSelection(emotionId)
        val snapshot = PlayerSnapshot(
            connection,
            RuntimeEntityId.of(1),
            alive = true,
            spectator = false,
            invisible = false,
            dimensionId = 1,
            regionKey = 1,
        )

        shouldThrow<IllegalStateException> { runtime.activeSessionCount }
        shouldThrow<IllegalStateException> { runtime.open(connection) }
        shouldThrow<IllegalStateException> { runtime.receiveClientHello(connection, ClientHello(capabilities)) }
        shouldThrow<IllegalStateException> { runtime.select(snapshot, selection) }
        shouldThrow<IllegalStateException> { runtime.close(connection) }
        shouldThrow<IllegalStateException> { runtime.clear() }
    }
})

private data class RecordedPaperPlay(
    val connection: ConnectionKey,
    val play: EmotionPlay,
)

private class RecordingPaperOutboundTransport : OutboundTransport {
    var helloStatus = OutboundDeliveryStatus.SENT
    var helloFailure: RuntimeException? = null
    val hellos = mutableListOf<ConnectionKey>()
    val plays = mutableListOf<RecordedPaperPlay>()

    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        return { connection ->
            hellos += connection
            helloFailure?.let { failure -> throw failure }
            helloStatus
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus = OutboundDeliveryStatus.SENT

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery {
        return { playerId, connectionId ->
            plays += RecordedPaperPlay(ConnectionKey(playerId, connectionId), play)
            OutboundDeliveryStatus.SENT
        }
    }
}

private fun connection(playerId: UUID, connectionId: Long): ConnectionKey =
    ConnectionKey(playerId, ConnectionId.of(connectionId))
