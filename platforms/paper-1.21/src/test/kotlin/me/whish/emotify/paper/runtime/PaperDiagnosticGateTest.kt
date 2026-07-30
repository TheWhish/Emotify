package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class PaperDiagnosticGateTest : FunSpec({
    test("diagnostic burst and refill remain bounded") {
        val time = FakeMonotonicTimeSource()
        val gate = PaperDiagnosticGate(2, 1, time)

        repeat(2) { gate.tryAdmit() shouldBe true }
        repeat(10_000) { gate.tryAdmit() shouldBe false }
        time.advanceBy(999.milliseconds)
        gate.tryAdmit() shouldBe false
        time.advanceBy(1.milliseconds)
        gate.tryAdmit() shouldBe true
    }
})
