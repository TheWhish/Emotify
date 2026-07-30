package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.paper.network.PaperProtocolChannels
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.GlobalSelectionIngressRelease
import me.whish.emotify.wire.v1.ProtocolV1Channels

@Suppress("unused")
class PaperConnectionIngressTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val hello = ClientHello(capabilities)
    val emotionId = EmotionId.of("emotify:happy")
    val catalog = EmotionCatalog.of(listOf(emotionId))

    fun beginActive(
        ingress: PaperConnectionIngress,
        playerId: UUID = UUID.randomUUID(),
        connectionIdentity: Any = Any(),
    ) = ingress.begin(playerId, connectionIdentity, PaperProtocolChannels.outgoing).also { connection ->
        ingress.activateProtocol(connection) shouldBe true
    }

    test("duplicate hello spam decodes at most the bounded burst and admits one call") {
        val ingress = PaperConnectionIngress(catalog, FakeMonotonicTimeSource())
        val connection = beginActive(ingress)
        var decoded = 0
        var admitted = 0

        repeat(10_000) {
            when (ingress.admitClientHello(connection) { decoded += 1; hello }) {
                is PaperClientHelloIngress.Admitted -> admitted += 1
                PaperClientHelloIngress.DUPLICATE_OR_BLOCKED,
                PaperClientHelloIngress.PROTOCOL_INACTIVE,
                PaperClientHelloIngress.RATE_LIMITED,
                PaperClientHelloIngress.STALE_CONNECTION,
                -> Unit
            }
        }

        decoded shouldBe 2
        admitted shouldBe 1
    }

    test("one changed hello is admitted and terminally blocks later packets") {
        val time = FakeMonotonicTimeSource()
        val ingress = PaperConnectionIngress(catalog, time)
        val connection = beginActive(ingress)
        val changed = ClientHello(ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags.NONE))

        ingress.admitClientHello(connection) { hello }.shouldBeInstanceOf<PaperClientHelloIngress.Admitted>()
        ingress.admitClientHello(connection) { changed }.shouldBeInstanceOf<PaperClientHelloIngress.Admitted>()
        time.advanceBy(1.seconds)
        ingress.admitClientHello(connection) { hello } shouldBe
            PaperClientHelloIngress.DUPLICATE_OR_BLOCKED
    }

    test("selection spam is limited before decode on the direct main thread path") {
        val ingress = PaperConnectionIngress(catalog, FakeMonotonicTimeSource())
        val connection = beginActive(ingress)
        var decoded = 0
        var admitted = 0

        repeat(10_000) {
            when (val result = ingress.admitSelection(connection) {
                decoded += 1
                EmotionSelection(emotionId)
            }) {
                is PaperSelectionIngress.Admitted -> {
                    admitted += 1
                    result.lease.release()
                }
                PaperSelectionIngress.RATE_LIMITED,
                PaperSelectionIngress.PROTOCOL_INACTIVE,
                PaperSelectionIngress.STALE_CONNECTION,
                PaperSelectionIngress.UNKNOWN_EMOTION,
                -> Unit
            }
        }

        decoded shouldBe 3
        admitted shouldBe 3
    }

    test("unknown selections consume both per connection and global request budgets") {
        val time = FakeMonotonicTimeSource()
        val global = GlobalSelectionIngressBudget(
            maxOutstanding = 8,
            requestBurstCapacity = 4,
            requestRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val ingress = PaperConnectionIngress(catalog, time, global)
        val first = beginActive(ingress)
        val second = beginActive(ingress)
        val unknown = EmotionId.of("external:unknown")
        var unknownResults = 0

        repeat(3) {
            if (ingress.admitSelection(first) { EmotionSelection(unknown) } ==
                PaperSelectionIngress.UNKNOWN_EMOTION
            ) {
                unknownResults += 1
            }
        }
        repeat(3) {
            if (ingress.admitSelection(second) { EmotionSelection(unknown) } ==
                PaperSelectionIngress.UNKNOWN_EMOTION
            ) {
                unknownResults += 1
            }
        }

        unknownResults shouldBe 4
        global.snapshot().availableRequestTokens shouldBe 0
        global.snapshot().outstanding shouldBe 0
    }

    test("connection generation rejects stale payloads close calls and outbound identity") {
        val ingress = PaperConnectionIngress(catalog, FakeMonotonicTimeSource())
        val playerId = UUID.randomUUID()
        val firstIdentity = Any()
        val secondIdentity = Any()
        val first = ingress.begin(playerId, firstIdentity)
        val second = ingress.begin(playerId, secondIdentity)
        var decoded = false

        ingress.admitClientHello(first) {
            decoded = true
            hello
        } shouldBe PaperClientHelloIngress.STALE_CONNECTION
        decoded shouldBe false
        ingress.isActive(first) shouldBe false
        ingress.isActive(second) shouldBe true
        ingress.current(playerId, firstIdentity) shouldBe null
        ingress.current(playerId, secondIdentity) shouldBe second
        ingress.close(first) shouldBe false
        ingress.close(second) shouldBe true
        ingress.current(playerId, secondIdentity) shouldBe null
    }

    test("protocol packets stay undecoded until activation and after channel loss") {
        val time = FakeMonotonicTimeSource()
        val global = GlobalSelectionIngressBudget(
            maxOutstanding = 2,
            requestBurstCapacity = 2,
            requestRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val ingress = PaperConnectionIngress(catalog, time, global)
        val connection = ingress.begin(UUID.randomUUID(), Any(), PaperProtocolChannels.outgoing)
        var helloDecodes = 0
        var selectionDecodes = 0

        ingress.admitClientHello(connection) {
            helloDecodes += 1
            hello
        } shouldBe PaperClientHelloIngress.PROTOCOL_INACTIVE
        ingress.admitSelection(connection) {
            selectionDecodes += 1
            EmotionSelection(emotionId)
        } shouldBe PaperSelectionIngress.PROTOCOL_INACTIVE
        helloDecodes shouldBe 0
        selectionDecodes shouldBe 0
        global.snapshot().availableRequestTokens shouldBe 2

        ingress.activateProtocol(connection) shouldBe true
        ingress.admitClientHello(connection) { helloDecodes += 1; hello }
            .shouldBeInstanceOf<PaperClientHelloIngress.Admitted>()
        ingress.unregisterOutgoingChannel(connection, ProtocolV1Channels.PLAY) shouldBe true
        ingress.admitSelection(connection) {
            selectionDecodes += 1
            EmotionSelection(emotionId)
        } shouldBe PaperSelectionIngress.PROTOCOL_INACTIVE
        helloDecodes shouldBe 1
        selectionDecodes shouldBe 0
        global.snapshot().availableRequestTokens shouldBe 2
    }

    test("clear invalidates admitted global leases without reusing connection ids") {
        val ingress = PaperConnectionIngress(catalog, FakeMonotonicTimeSource())
        val playerId = UUID.randomUUID()
        val first = beginActive(ingress, playerId)
        val admitted = ingress.admitSelection(first) { EmotionSelection(emotionId) }
            .shouldBeInstanceOf<PaperSelectionIngress.Admitted>()

        ingress.clear() shouldBe 1
        admitted.lease.release() shouldBe GlobalSelectionIngressRelease.STALE_AFTER_RESET
        val second = ingress.begin(playerId, Any())
        (second.connectionId.value > first.connectionId.value) shouldBe true
    }

    test("outgoing channel mask changes without replacing the connection generation") {
        val ingress = PaperConnectionIngress(catalog, FakeMonotonicTimeSource())
        val playerId = UUID.randomUUID()
        val identity = Any()
        val connection = ingress.begin(playerId, identity, listOf(ProtocolV1Channels.SERVER_HELLO))

        ingress.supportsOutgoingChannel(connection, ProtocolV1Channels.SERVER_HELLO) shouldBe true
        ingress.supportsAllOutgoingChannels(connection) shouldBe false
        ingress.registerOutgoingChannel(connection, ProtocolV1Channels.PLAY) shouldBe true
        ingress.registerOutgoingChannel(connection, ProtocolV1Channels.SELECTION_REJECTED) shouldBe true
        ingress.supportsAllOutgoingChannels(connection) shouldBe true
        ingress.activateProtocol(connection) shouldBe true

        ingress.unregisterOutgoingChannel(connection, ProtocolV1Channels.PLAY) shouldBe true
        ingress.supportsOutgoingChannel(connection, ProtocolV1Channels.PLAY) shouldBe false
        ingress.isProtocolActive(connection) shouldBe false
        ingress.current(playerId, identity) shouldBe connection
    }
})
