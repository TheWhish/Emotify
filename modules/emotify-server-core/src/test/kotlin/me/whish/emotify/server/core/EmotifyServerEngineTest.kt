package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EventSequence

@Suppress("unused")
class EmotifyServerEngineTest : FunSpec({
    test("unknown stale and pre-handshake selections are ignored") {
        val harness = engineHarness()
        val connection = testConnection(1L)
        val player = testPlayer(connection)

        harness.engine.select(player, TEST_HAPPY) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.STALE_CONNECTION)
        harness.engine.open(connection)
        harness.engine.select(player, TEST_HAPPY) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.HANDSHAKE_INCOMPLETE)
        harness.engine.receiveClientHello(connection, TEST_CLIENT_HELLO)
        harness.engine.select(player, TEST_UNKNOWN) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.UNKNOWN_EMOTION)
        harness.transport.plays.size shouldBe 0
    }

    test("hello and close operations reject stale connection generations") {
        val harness = engineHarness()
        val playerId = testConnection(1L).playerId
        val first = testConnection(1L, playerId)
        val second = testConnection(2L, playerId)
        harness.engine.open(first).hello.status shouldBe OutboundDeliveryStatus.SENT
        harness.engine.open(second).hello.status shouldBe OutboundDeliveryStatus.SENT

        harness.engine.receiveClientHello(first, TEST_CLIENT_HELLO) shouldBe ServerHelloResult.StaleConnection
        harness.engine.close(first) shouldBe ServerCloseResult.STALE_OR_MISSING
        harness.engine.receiveClientHello(second, TEST_CLIENT_HELLO)
            .shouldBeInstanceOf<ServerHelloResult.Processed>()
        harness.engine.close(second) shouldBe ServerCloseResult.CLOSED
    }

    test("policy rejection touches neither sequence nor audience budget") {
        val time = FakeMonotonicTimeSource()
        val sequence = ServerEventSequence()
        val audienceBudget = AudienceBudget(
            globalCapacity = 1,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val harness = engineHarness(
            time = time,
            audienceBudget = audienceBudget,
            sequence = sequence,
            policy = TEST_ENABLED_POLICY.copy(enabled = false),
        )
        val connection = testConnection(1L)
        harness.openSupported(connection)

        val rejection = harness.engine.select(testPlayer(connection), TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        rejection.reason shouldBe SelectionRejectionReason.SERVER_DISABLED
        sequence.nextOrNull() shouldBe EventSequence.of(1L)
        audienceBudget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
    }

    test("sequence exhaustion is checked before audience capacity") {
        val time = FakeMonotonicTimeSource()
        val sequence = ServerEventSequence(Long.MAX_VALUE)
        val audienceBudget = AudienceBudget(
            globalCapacity = 1,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val harness = engineHarness(time = time, audienceBudget = audienceBudget, sequence = sequence)
        val connection = testConnection(1L)
        harness.openSupported(connection)

        val rejection = harness.engine.select(testPlayer(connection), TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        rejection.reason shouldBe SelectionRejectionReason.SERVER_BUSY
        audienceBudget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
    }

    test("zero delivery refunds audience capacity and leaves cooldown uncommitted") {
        val time = FakeMonotonicTimeSource()
        val audienceBudget = AudienceBudget(
            globalCapacity = 1,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val transport = RecordingOutboundTransport().also {
            it.playResponder = { _, _, _ -> OutboundDeliveryStatus.FAILED }
        }
        val harness = engineHarness(time = time, audienceBudget = audienceBudget, transport = transport)
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)

        val first = harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Undelivered>()
        val second = harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Undelivered>()

        first.play.sequence shouldBe EventSequence.of(1L)
        second.play.sequence shouldBe EventSequence.of(2L)
        first.failedRecipients shouldBe 1
        second.failedRecipients shouldBe 1
        transport.plays.size shouldBe 2
    }

    test("runtime traversal failure after delivery is typed and commits cooldown") {
        val failure = IllegalStateException("synthetic traversal failure")
        val harness = engineHarness()
        harness.audiencePort.delegate = AudiencePort { _, _, _ -> throw failure }
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)

        val published = harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        published.deliveredRecipients shouldBe 1
        published.traversal shouldBe AudienceTraversalOutcome.Failed(failure)
        harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
            .reason shouldBe SelectionRejectionReason.COOLDOWN
    }

    test("fatal traversal after delivery commits before propagating") {
        val harness = engineHarness()
        harness.audiencePort.delegate = AudiencePort { _, _, _ -> throw AssertionError("synthetic fatal traversal") }
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)

        shouldThrow<AssertionError> { harness.engine.select(player, TEST_HAPPY) }

        harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
            .reason shouldBe SelectionRejectionReason.COOLDOWN
    }

    test("fatal traversal without delivery refunds capacity and leaves cooldown open") {
        val transport = RecordingOutboundTransport().also {
            it.playResponder = { _, _, _ -> OutboundDeliveryStatus.FAILED }
        }
        val harness = engineHarness(transport = transport)
        harness.audiencePort.delegate = AudiencePort { _, _, _ -> throw AssertionError("synthetic fatal traversal") }
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)

        shouldThrow<AssertionError> { harness.engine.select(player, TEST_HAPPY) }
        shouldThrow<AssertionError> { harness.engine.select(player, TEST_HAPPY) }

        transport.plays.size shouldBe 2
    }

    test("runtime send failures are captured and recipient play tokens are refunded") {
        val failure = IllegalStateException("synthetic send failure")
        val transport = RecordingOutboundTransport().also {
            it.playResponder = { _, _, _ -> throw failure }
        }
        val harness = engineHarness(transport = transport)
        val sourceConnection = testConnection(1L)
        val recipientConnection = testConnection(2L)
        harness.openSupported(sourceConnection)
        harness.openSupported(recipientConnection)
        harness.audiencePort.delegate = candidateAudiencePort(
            listOf(AudienceCandidateFixture(recipientConnection)),
        )
        val source = testPlayer(sourceConnection)

        repeat(40) {
            val undelivered = harness.engine.select(source, TEST_HAPPY)
                .shouldBeInstanceOf<ServerSelectionResult.Undelivered>()
            undelivered.failedRecipients shouldBe 2
            undelivered.throttledRecipients shouldBe 0
            undelivered.firstSendFailure shouldBe failure
        }

        transport.plays.count { it.playerId == sourceConnection.playerId } shouldBe 40
        transport.plays.count { it.playerId == recipientConnection.playerId } shouldBe 40
    }

    test("fanout visitor enforces the hard boundary even against a noncompliant port") {
        val harness = engineHarness()
        var visitorCalls = 0
        harness.audiencePort.delegate = AudiencePort { _, _, visitor ->
            repeat(1_000) { index ->
                visitorCalls += 1
                if (!visitor.visit(
                        testConnection(index.toLong() + 10L).playerId,
                        ConnectionId.of(index.toLong() + 10L),
                        true,
                        true,
                        1.0,
                    )
                ) {
                    return@AudiencePort AudienceVisitCompletion.EXHAUSTED
                }
            }
            AudienceVisitCompletion.EXHAUSTED
        }
        val connection = testConnection(1L)
        harness.openSupported(connection)

        val result = harness.engine.select(testPlayer(connection), TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        visitorCalls shouldBe AudiencePolicy.MAX_TRACKING_CANDIDATES
        result.visitedCandidates shouldBe AudiencePolicy.MAX_TRACKING_CANDIDATES
        result.traversal shouldBe AudienceTraversalOutcome.Completed(AudienceVisitCompletion.LIMIT_REACHED)
        result.deliveredRecipients shouldBe 1
    }

    test("rejection delivery has a separate bounded response budget") {
        val harness = engineHarness(policy = TEST_ENABLED_POLICY.copy(enabled = false))
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)

        repeat(2) {
            harness.engine.select(player, TEST_HAPPY)
                .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
                .dispatch.shouldBeInstanceOf<RejectionDispatch.Attempted>()
        }
        harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
            .dispatch shouldBe RejectionDispatch.RateLimited
        harness.transport.rejections.size shouldBe 2
    }

    test("policy replacement preserves sessions and cooldown state") {
        val harness = engineHarness()
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection)
        harness.engine.select(player, TEST_HAPPY).shouldBeInstanceOf<ServerSelectionResult.Published>()
        val disabled = TEST_ENABLED_POLICY.copy(enabled = false)

        val replacement = harness.engine.replacePolicy(disabled)
        val rejection = harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        replacement shouldBe ServerPolicyReplacement(TEST_ENABLED_POLICY, disabled)
        rejection.reason shouldBe SelectionRejectionReason.SERVER_DISABLED
        harness.engine.activeSessionCount shouldBe 1
    }

    test("runtime configuration refresh preserves sessions cooldown and abuse state") {
        val harness = engineHarness()
        val connection = testConnection(1L)
        val player = testPlayer(connection)
        harness.openSupported(connection)
        harness.engine.select(player, TEST_HAPPY).shouldBeInstanceOf<ServerSelectionResult.Published>()
        harness.time.advanceBy(400.milliseconds)
        val allowed = EmotionCatalog.of(listOf(TEST_HAPPY))
        val replacement = ServerRuntimeConfiguration(
            TEST_SERVER_HELLO.copy(cooldownMillis = 3_000, emotionCatalog = allowed),
            ServerSelectionPolicy(true, TEST_CATALOG, allowed),
            ServerAudiencePolicy(radius = 16.0, maximumTrackingCandidates = 32),
        )

        val changed = harness.engine.replaceConfiguration(replacement)
        val refresh = harness.engine.refreshServerHello()
        val cooldown = harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        changed.current shouldBe replacement
        refresh shouldBe ServerHelloRefreshResult(1, 1, 0, 0, null)
        harness.transport.hellos.last() shouldBe (connection to replacement.serverHello)
        cooldown.reason shouldBe SelectionRejectionReason.COOLDOWN
        cooldown.retryAfterMillis shouldBe 2_600
        harness.engine.select(player, TEST_LOVE)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
            .reason shouldBe SelectionRejectionReason.EMOTION_DISABLED
        harness.engine.activeSessionCount shouldBe 1
    }

    test("reload refreshes pending and supported sessions once while excluding terminal sessions") {
        val harness = engineHarness()
        val pending = testConnection(1L)
        val supported = testConnection(2L)
        val unsupported = testConnection(3L)
        harness.engine.open(pending)
        harness.openSupported(supported)
        harness.engine.open(unsupported)
        harness.engine.receiveClientHello(
            unsupported,
            ClientHello(ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE)),
        )
        val replacement = ServerRuntimeConfiguration(
            TEST_SERVER_HELLO.copy(cooldownMillis = 3_000),
            TEST_ENABLED_POLICY,
        )
        val preparationsBeforeRefresh = harness.transport.preparedHelloCount

        harness.engine.replaceConfiguration(replacement)
        val refresh = harness.engine.refreshServerHello()
        val pendingHandshake = harness.engine.receiveClientHello(pending, TEST_CLIENT_HELLO)

        refresh shouldBe ServerHelloRefreshResult(2, 2, 0, 0, null)
        harness.transport.preparedHelloCount shouldBe preparationsBeforeRefresh + 1
        harness.transport.hellos.takeLast(2).map { recorded -> recorded.first }.toSet() shouldBe
            setOf(pending, supported)
        harness.transport.hellos.last { recorded -> recorded.first == pending }.second shouldBe replacement.serverHello
        pendingHandshake.shouldBeInstanceOf<ServerHelloResult.Processed>().transition shouldBe
            ServerHandshakeTransition.SUPPORTED
    }

    test("one play is prepared once for self and every tracked recipient") {
        val harness = engineHarness()
        val source = testConnection(1L)
        val recipients = (2L..33L).map(::testConnection)
        harness.openSupported(source)
        recipients.forEach(harness::openSupported)
        harness.audiencePort.delegate = candidateAudiencePort(recipients.map(::AudienceCandidateFixture))

        harness.engine.select(testPlayer(source), TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Published>()
            .deliveredRecipients shouldBe 33

        harness.transport.preparedPlayCount shouldBe 1
        harness.transport.plays.size shouldBe 33
    }

    test("publish permission is enforced inside the server selection policy") {
        val harness = engineHarness()
        val connection = testConnection(1L)
        harness.openSupported(connection)
        val player = testPlayer(connection).copy(permittedToPublish = false)

        harness.engine.select(player, TEST_HAPPY)
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
            .reason shouldBe SelectionRejectionReason.PLAYER_STATE
        harness.transport.plays.size shouldBe 0
    }

    test("clear closes sessions and resets every process-owned budget") {
        val time = FakeMonotonicTimeSource()
        val ingress = GlobalSelectionIngressBudget(
            maxOutstanding = 1,
            requestBurstCapacity = 2,
            requestRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val audienceBudget = AudienceBudget(timeSource = time)
        val sequence = ServerEventSequence()
        val harness = engineHarness(
            time = time,
            audienceBudget = audienceBudget,
            sequence = sequence,
            ingressBudget = ingress,
        )
        val first = testConnection(1L)
        val second = testConnection(2L)
        harness.openSupported(first)
        harness.openSupported(second)
        harness.engine.select(testPlayer(first), TEST_HAPPY).shouldBeInstanceOf<ServerSelectionResult.Published>()
        val lease = ingress.tryAcquire()
            .shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
            .lease

        harness.engine.clear() shouldBe ServerClearResult(2)

        harness.engine.activeSessionCount shouldBe 0
        sequence.nextOrNull() shouldBe EventSequence.of(1L)
        audienceBudget.trackedRegionCount shouldBe 0
        ingress.snapshot() shouldBe GlobalSelectionIngressSnapshot(0, 2)
        lease.release() shouldBe GlobalSelectionIngressRelease.STALE_AFTER_RESET
        harness.engine.select(testPlayer(first), TEST_HAPPY) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.STALE_CONNECTION)
    }
})
