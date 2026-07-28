package me.whish.emotify.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello

class ServerSelectionPreparationTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val happy = EmotionId.of("emotify:happy")
    val policy = ServerSelectionPolicy(true, EmotionCatalog.BUILT_IN, EmotionCatalog.BUILT_IN)
    val playerState = PlayerSelectionState(true, false, false)

    fun session(time: FakeMonotonicTimeSource = FakeMonotonicTimeSource()) =
        ServerPlayerSession(capabilities, 1_200.milliseconds, time).also {
            it.receiveClientHello(ClientHello(capabilities))
        }

    test("preparation does not consume cooldown until committed") {
        val session = session()

        session.prepareSelection(happy, policy, playerState) shouldBe SelectionPreparation.Ready
        session.prepareSelection(happy, policy, playerState) shouldBe SelectionPreparation.Ready
        session.commitSelection()
        session.prepareSelection(happy, policy, playerState) shouldBe
            SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, 1_200)
    }

    test("recipient budget reserves self and keeps one token for self during external fanout") {
        val session = session()

        repeat(31) { session.tryAdmitPlay(self = false) shouldBe true }
        session.tryAdmitPlay(self = false) shouldBe false
        session.tryAdmitPlay(self = true) shouldBe true
        session.tryAdmitPlay(self = true) shouldBe false
    }

    test("synchronous send failure returns the reserved recipient token") {
        val session = session()

        repeat(32) { session.tryAdmitPlay(self = true) shouldBe true }
        session.tryAdmitPlay(self = true) shouldBe false
        session.refundPlay()

        session.tryAdmitPlay(self = true) shouldBe true
        session.tryAdmitPlay(self = true) shouldBe false
    }

    test("rejection retry validates protocol boundaries") {
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
