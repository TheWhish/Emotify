package me.whish.emotify.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class NetworkDiagnosticGateTest : FunSpec({
    test("failure diagnostics stay bounded and refill gradually") {
        val time = FakeMonotonicTimeSource()
        val gate = NetworkDiagnosticGate(timeSource = time)
        var admitted = 0

        repeat(10_000) {
            if (gate.tryAdmit()) {
                admitted += 1
            }
        }
        admitted shouldBe NetworkDiagnosticGate.DEFAULT_CAPACITY
        time.advanceBy(499.milliseconds)
        gate.tryAdmit() shouldBe false
        time.advanceBy(1.milliseconds)
        gate.tryAdmit() shouldBe true
        gate.tryAdmit() shouldBe false
    }
})
