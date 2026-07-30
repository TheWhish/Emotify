package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.AudiencePort
import me.whish.emotify.server.core.AudienceVisitCompletion
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.EmotifyServerEngine
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PreparedEmotionDelivery
import me.whish.emotify.server.core.PreparedServerHelloDelivery
import me.whish.emotify.server.core.ServerRuntimeConfiguration
import me.whish.emotify.server.core.ServerSelectionPolicy

@Suppress("unused")
class PaperPolicyRefreshQueueTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val catalog = EmotionCatalog.of(listOf(EmotionId.of("emotify:happy")))
    val hello = ServerHello(capabilities, 2_200, catalog)
    val policy = ServerSelectionPolicy(true, catalog, catalog)

    fun connection(index: Long): ConnectionKey = ConnectionKey(UUID(0L, index), ConnectionId.of(index))

    fun engine(transport: RefreshRecordingTransport): EmotifyServerEngine = EmotifyServerEngine(
        hello,
        policy,
        FakeMonotonicTimeSource(),
        AudiencePort { _, _, _ -> AudienceVisitCompletion.EXHAUSTED },
        transport,
    )

    test("one encoded policy is drained in bounded batches") {
        val transport = RefreshRecordingTransport()
        val engine = engine(transport)
        val connections = (1L..130L).map(::connection)
        connections.forEach(engine::open)
        transport.deliveries.clear()
        val preparationsBefore = transport.preparations
        engine.replaceConfiguration(
            ServerRuntimeConfiguration(hello.copy(cooldownMillis = 3_000), policy),
        )
        val queue = PaperPolicyRefreshQueue(64)

        queue.replace(connections, engine.prepareServerHelloRefresh()) shouldBe 130
        queue.drain() shouldBe PaperPolicyRefreshBatchResult(64, 64, 0, 0, 66, null)
        queue.drain() shouldBe PaperPolicyRefreshBatchResult(64, 64, 0, 0, 2, null)
        queue.drain() shouldBe PaperPolicyRefreshBatchResult(2, 2, 0, 0, 0, null)

        transport.preparations shouldBe preparationsBefore + 1
        transport.deliveries.size shouldBe 130
        transport.deliveries.all { delivery -> delivery.second.cooldownMillis == 3_000 } shouldBe true
    }

    test("replacement coalesces pending work to the latest policy") {
        val transport = RefreshRecordingTransport()
        val engine = engine(transport)
        val connections = (1L..3L).map(::connection)
        connections.forEach(engine::open)
        transport.deliveries.clear()
        val queue = PaperPolicyRefreshQueue(1)
        engine.replaceConfiguration(
            ServerRuntimeConfiguration(hello.copy(cooldownMillis = 3_000), policy),
        )
        queue.replace(connections, engine.prepareServerHelloRefresh())
        queue.drain()
        engine.replaceConfiguration(
            ServerRuntimeConfiguration(hello.copy(cooldownMillis = 4_000), policy),
        )

        queue.replace(connections, engine.prepareServerHelloRefresh()) shouldBe 3
        repeat(3) { queue.drain() }

        transport.deliveries.drop(1).all { delivery -> delivery.second.cooldownMillis == 4_000 } shouldBe true
        queue.size shouldBe 0
    }

    test("stale and unsupported sessions become unavailable without aborting the batch") {
        val transport = RefreshRecordingTransport()
        val engine = engine(transport)
        val stale = connection(1L)
        val unsupported = connection(2L)
        val supported = connection(3L)
        listOf(stale, unsupported, supported).forEach(engine::open)
        engine.receiveClientHello(
            unsupported,
            ClientHello(ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE)),
        )
        engine.receiveClientHello(supported, ClientHello(capabilities))
        val queue = PaperPolicyRefreshQueue()
        queue.replace(listOf(stale, unsupported, supported), engine.prepareServerHelloRefresh())
        engine.close(stale)

        queue.drain() shouldBe PaperPolicyRefreshBatchResult(3, 1, 2, 0, 0, null)
    }
})

private class RefreshRecordingTransport : OutboundTransport {
    var preparations = 0
    val deliveries = mutableListOf<Pair<ConnectionKey, ServerHello>>()

    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        preparations += 1
        return { connection ->
            deliveries += connection to hello
            OutboundDeliveryStatus.SENT
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus = OutboundDeliveryStatus.SENT

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery = { _, _ ->
        OutboundDeliveryStatus.SENT
    }
}
