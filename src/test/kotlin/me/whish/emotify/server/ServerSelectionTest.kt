package me.whish.emotify.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello

class ServerSelectionTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val happy = EmotionId.of("emotify:happy")
    val love = EmotionId.of("emotify:love")
    val unknown = EmotionId.of("external:unknown")
    val readyPlayer = PlayerSelectionState(alive = true, spectator = false, invisible = false)
    val enabledPolicy = ServerSelectionPolicy(
        enabled = true,
        catalog = EmotionCatalog.BUILT_IN,
        allowedEmotions = EmotionCatalog.BUILT_IN,
    )

    fun supportedSession(time: FakeMonotonicTimeSource = FakeMonotonicTimeSource()): ServerPlayerSession =
        ServerPlayerSession(capabilities, 1_200.milliseconds, time).also { session ->
            session.receiveClientHello(ClientHello(capabilities))
        }

    test("selection before a supported handshake is ignored") {
        val session = ServerPlayerSession(capabilities, 1_200.milliseconds, FakeMonotonicTimeSource())

        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ignored
    }

    test("known allowed emotion starts cooldown only when committed") {
        val time = FakeMonotonicTimeSource()
        val session = supportedSession(time)

        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
        session.commitSelection()
        time.advanceBy(200.milliseconds)
        session.prepareSelection(love, enabledPolicy, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 1_000)
    }

    test("unknown emotion is ignored without consuming cooldown") {
        val session = supportedSession()

        session.prepareSelection(unknown, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ignored
        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("disabled server and allow list reject without consuming cooldown") {
        val disabled = enabledPolicy.copy(enabled = false)
        val restricted = enabledPolicy.copy(allowedEmotions = EmotionCatalog.of(listOf(love)))
        val session = supportedSession()

        session.prepareSelection(happy, disabled, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_DISABLED, 0)
        session.prepareSelection(happy, restricted, readyPlayer) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.EMOTION_DISABLED, 0)
        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("spectator dead and invisible players are rejected") {
        listOf(
            readyPlayer.copy(alive = false),
            readyPlayer.copy(spectator = true),
            readyPlayer.copy(invisible = true),
        ).forEach { state ->
            supportedSession().prepareSelection(happy, enabledPolicy, state) shouldBe
                SelectionPreparation.Rejected(SelectionRejectionReason.PLAYER_STATE, 0)
        }
    }

    test("abandoned capacity attempt does not consume sender cooldown") {
        val session = supportedSession()

        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
    }

    test("rejection responses have a separate bounded bucket") {
        val time = FakeMonotonicTimeSource()
        val session = supportedSession(time)

        session.tryAdmitRejection() shouldBe true
        session.tryAdmitRejection() shouldBe true
        session.tryAdmitRejection() shouldBe false
        time.advanceBy(1_000.milliseconds)
        session.tryAdmitRejection() shouldBe true
    }

    test("duplicate hello does not reset rejection response budget") {
        val session = supportedSession()
        session.tryAdmitRejection()
        session.tryAdmitRejection()

        session.receiveClientHello(ClientHello(capabilities)) shouldBe ServerHandshakeTransition.NO_CHANGE
        session.tryAdmitRejection() shouldBe false
    }

    test("cooldown retry rounds positive nanoseconds upward without overflow") {
        listOf(
            1.nanoseconds to 1,
            1_000_001.nanoseconds to 2,
            Long.MAX_VALUE.nanoseconds to 10_000,
        ).forEach { (cooldown, expectedMillis) ->
            val session = ServerPlayerSession(capabilities, cooldown, FakeMonotonicTimeSource())
            session.receiveClientHello(ClientHello(capabilities))
            session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
            session.commitSelection()
            session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe
                SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, expectedMillis)
        }
    }

    test("selection is ready at the exact cooldown boundary") {
        val time = FakeMonotonicTimeSource()
        val session = supportedSession(time)
        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
        session.commitSelection()

        time.advanceBy(1_200.milliseconds)

        session.prepareSelection(happy, enabledPolicy, readyPlayer) shouldBe SelectionPreparation.Ready
    }
})
