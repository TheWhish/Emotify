package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello

@Suppress("unused")
class ServerPlayerSessionTest : FunSpec({
    fun session(time: FakeMonotonicTimeSource = FakeMonotonicTimeSource()): ServerPlayerSession =
        ServerPlayerSession(TEST_CAPABILITIES, 1_200.milliseconds, time).also {
            it.receiveClientHello(TEST_CLIENT_HELLO)
        }

    val readyPlayer = testPlayer(testConnection(1L))

    test("first compatible hello establishes the negotiated protocol") {
        val playerSession = ServerPlayerSession(
            TEST_CAPABILITIES,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
        )

        playerSession.receiveClientHello(TEST_CLIENT_HELLO) shouldBe ServerHandshakeTransition.SUPPORTED
        playerSession.handshakeState
            .shouldBeInstanceOf<ServerHandshakeState.Supported>()
            .negotiated.version shouldBe ProtocolVersion.CURRENT
    }

    test("duplicate hello preserves cooldown and rate-limit state") {
        val time = FakeMonotonicTimeSource()
        val playerSession = session(time)
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
        playerSession.commitSelection()
        playerSession.tryAdmitRejection() shouldBe true
        playerSession.tryAdmitRejection() shouldBe true
        time.advanceBy(200.milliseconds)

        playerSession.receiveClientHello(TEST_CLIENT_HELLO) shouldBe ServerHandshakeTransition.NO_CHANGE
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 1_000)
        playerSession.tryAdmitRejection() shouldBe false
    }

    test("changed repeat permanently marks the session unsupported") {
        val playerSession = session()
        val changed = ClientHello(
            ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags.NONE),
        )

        playerSession.receiveClientHello(changed) shouldBe ServerHandshakeTransition.UNSUPPORTED
        playerSession.handshakeState shouldBe ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.CHANGED_CLIENT_CAPABILITIES,
            TEST_CAPABILITIES,
        )
        playerSession.receiveClientHello(TEST_CLIENT_HELLO) shouldBe ServerHandshakeTransition.NO_CHANGE
    }

    test("major mismatch retains the incompatible capability") {
        val incompatible = ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE)
        val playerSession = ServerPlayerSession(
            TEST_CAPABILITIES,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
        )

        playerSession.receiveClientHello(ClientHello(incompatible)) shouldBe ServerHandshakeTransition.UNSUPPORTED
        playerSession.handshakeState shouldBe ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.INCOMPATIBLE_PROTOCOL,
            incompatible,
        )
    }

    test("pre-handshake and unknown selections are ignored without cooldown") {
        val playerSession = ServerPlayerSession(
            TEST_CAPABILITIES,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
        )

        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ignored
        playerSession.receiveClientHello(TEST_CLIENT_HELLO)
        playerSession.prepareSelection(TEST_UNKNOWN, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ignored
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("policy and player-state rejections never consume cooldown") {
        val playerSession = session()
        val disabled = TEST_ENABLED_POLICY.copy(enabled = false)
        val restricted = TEST_ENABLED_POLICY.copy(allowedEmotions = EmotionCatalog.of(listOf(TEST_LOVE)))

        playerSession.prepareSelection(TEST_HAPPY, disabled, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_DISABLED, 0)
        playerSession.prepareSelection(TEST_HAPPY, restricted, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.EMOTION_DISABLED, 0)
        listOf(
            readyPlayer.copy(alive = false),
            readyPlayer.copy(spectator = true),
            readyPlayer.copy(invisible = true),
        ).forEach { state ->
            playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, state) shouldBe
                SelectionPreparation.Rejected(SelectionRejectionReason.PLAYER_STATE, 0)
        }
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("cooldown begins only on commit and rounds retry upward") {
        val time = FakeMonotonicTimeSource()
        val playerSession = session(time)

        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
        playerSession.commitSelection()
        time.advanceBy(1_000_001.nanoseconds)
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 1_199)
        time.advanceBy(1_198_999_999.nanoseconds)
        playerSession.prepareSelection(TEST_HAPPY, TEST_ENABLED_POLICY, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("rejection responses refill independently") {
        val time = FakeMonotonicTimeSource()
        val playerSession = session(time)

        playerSession.tryAdmitRejection() shouldBe true
        playerSession.tryAdmitRejection() shouldBe true
        playerSession.tryAdmitRejection() shouldBe false
        time.advanceBy(1_000.milliseconds)
        playerSession.tryAdmitRejection() shouldBe true
    }

    test("recipient fanout retains one self token and refunds failures") {
        val playerSession = session()

        repeat(31) { playerSession.tryAdmitPlay(self = false) shouldBe true }
        playerSession.tryAdmitPlay(self = false) shouldBe false
        playerSession.tryAdmitPlay(self = true) shouldBe true
        playerSession.tryAdmitPlay(self = true) shouldBe false
        playerSession.refundPlay()
        playerSession.tryAdmitPlay(self = true) shouldBe true
    }

    test("selection rejection validates protocol retry boundaries") {
        SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 0)
        SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 10_000)

        shouldThrow<IllegalArgumentException> {
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, -1)
        }
        shouldThrow<IllegalArgumentException> {
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 10_001)
        }
    }
})
