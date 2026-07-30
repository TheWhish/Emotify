package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class ClientSelectionAttemptGateTest : FunSpec({
    test("client pacing matches the server rejection response budget") {
        val time = FakeMonotonicTimeSource()
        val gate = ClientSelectionAttemptGate(time)

        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe false

        time.advanceBy(999.milliseconds)
        gate.tryAdmit() shouldBe false
        time.advanceBy(1.milliseconds)
        gate.tryAdmit() shouldBe true
    }

    test("reset starts a fresh connection budget") {
        val gate = ClientSelectionAttemptGate(FakeMonotonicTimeSource())
        gate.tryAdmit()
        gate.tryAdmit()

        gate.reset()

        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe true
    }

    test("failed transport send refunds the consumed attempt") {
        val gate = ClientSelectionAttemptGate(FakeMonotonicTimeSource())
        gate.tryAdmit() shouldBe true
        gate.refund()

        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe false
    }
})
