package me.whish.emotify.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello

class ServerSessionRegistryTest : FunSpec({
    val serverCapabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val compatibleHello = ClientHello(serverCapabilities)
    val happy = EmotionId.of("emotify:happy")
    val policy = ServerSelectionPolicy(true, EmotionCatalog.BUILT_IN, EmotionCatalog.BUILT_IN)
    val playerState = PlayerSelectionState(alive = true, spectator = false, invisible = false)

    test("first compatible hello establishes protocol one") {
        val session = ServerPlayerSession(serverCapabilities, 1_200.milliseconds, FakeMonotonicTimeSource())

        session.receiveClientHello(compatibleHello) shouldBe ServerHandshakeTransition.SUPPORTED

        val supported = session.handshakeState.shouldBeInstanceOf<ServerHandshakeState.Supported>()
        supported.negotiated.version shouldBe ProtocolVersion.CURRENT
    }

    test("duplicate hello is a no-op and does not reset cooldown state") {
        val time = FakeMonotonicTimeSource()
        val session = ServerPlayerSession(serverCapabilities, 1_200.milliseconds, time)
        session.receiveClientHello(compatibleHello)
        session.prepareSelection(happy, policy, playerState) shouldBe SelectionPreparation.Ready
        session.commitSelection()
        time.advanceBy(200.milliseconds)

        session.receiveClientHello(compatibleHello) shouldBe ServerHandshakeTransition.NO_CHANGE
        session.prepareSelection(happy, policy, playerState) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 1_000)
    }

    test("changed repeat makes the existing session incompatible") {
        val session = ServerPlayerSession(serverCapabilities, 1_200.milliseconds, FakeMonotonicTimeSource())
        val changed = ClientHello(
            ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags.NONE),
        )
        session.receiveClientHello(compatibleHello)

        session.receiveClientHello(changed) shouldBe ServerHandshakeTransition.UNSUPPORTED
        session.handshakeState shouldBe ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.CHANGED_CLIENT_CAPABILITIES,
            compatibleHello.capabilities,
        )
    }

    test("major mismatch is retained as an incompatible capability") {
        val session = ServerPlayerSession(serverCapabilities, 1_200.milliseconds, FakeMonotonicTimeSource())
        val incompatible = ClientHello(
            ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE),
        )

        session.receiveClientHello(incompatible) shouldBe ServerHandshakeTransition.UNSUPPORTED
        session.handshakeState shouldBe ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.INCOMPATIBLE_PROTOCOL,
            incompatible.capabilities,
        )
    }

    test("reconnect creates a clean session and logout removes it") {
        val playerId = UUID.randomUUID()
        val time = FakeMonotonicTimeSource()
        val registry = ServerSessionRegistry(serverCapabilities, 1_200.milliseconds, time)
        val first = registry.open(playerId, connectionId = 1L)
        first.receiveClientHello(compatibleHello)
        first.prepareSelection(happy, policy, playerState) shouldBe SelectionPreparation.Ready
        first.commitSelection()

        registry.close(playerId, connectionId = 1L) shouldBe true
        registry.size shouldBe 0
        val reconnected = registry.open(playerId, connectionId = 2L)

        reconnected.handshakeState shouldBe ServerHandshakeState.Pending
        reconnected.receiveClientHello(compatibleHello)
        reconnected.prepareSelection(happy, policy, playerState) shouldBe SelectionPreparation.Ready
    }

    test("opening the same active player twice fails fast") {
        val playerId = UUID.randomUUID()
        val registry = ServerSessionRegistry(serverCapabilities, 1_200.milliseconds, FakeMonotonicTimeSource())
        registry.open(playerId, connectionId = 1L)

        shouldThrow<IllegalStateException> {
            registry.open(playerId, connectionId = 1L)
        }
    }

    test("stale logout cannot remove a reconnected player session") {
        val playerId = UUID.randomUUID()
        val registry = ServerSessionRegistry(serverCapabilities, 1_200.milliseconds, FakeMonotonicTimeSource())
        registry.open(playerId, connectionId = 1L)
        val reconnected = registry.open(playerId, connectionId = 2L)

        registry.close(playerId, connectionId = 1L) shouldBe false
        registry.get(playerId, connectionId = 2L) shouldBe reconnected
    }
})
