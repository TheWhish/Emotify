package me.whish.emotify.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

class ClientSelectionResponseGateTest : FunSpec({
    test("only one selection can wait for a response") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())

        gate.tryReserve() shouldBe true
        gate.tryReserve() shouldBe false
    }

    test("successful self play releases the pending selection") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve()

        gate.tryConsumeSuccess() shouldBe true
        gate.tryReserve() shouldBe true
    }

    test("rejection releases the pending selection") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve()

        gate.tryConsumeRejection() shouldBe true
        gate.tryConsumeRejection() shouldBe false
    }

    test("lost responses expire without blocking later selections") {
        val time = FakeMonotonicTimeSource()
        val gate = ClientSelectionResponseGate(time, pendingTimeoutNanos = 500.milliseconds.inWholeNanoseconds)
        gate.tryReserve()

        time.advanceBy(499.milliseconds)
        gate.tryReserve() shouldBe false
        time.advanceBy(1.milliseconds)
        gate.tryReserve() shouldBe true
    }

    test("cancel and reset clear pending state") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve()
        gate.cancelReservation()
        gate.tryReserve() shouldBe true

        gate.reset()
        gate.tryConsumeSuccess() shouldBe false
    }

    test("monotonic clock rollback fails fast") {
        val time = FakeMonotonicTimeSource(1_000L)
        val gate = ClientSelectionResponseGate(time)
        gate.tryReserve()
        time.rewindBy(1.milliseconds)

        shouldThrow<IllegalStateException> {
            gate.tryReserve()
        }
    }

    test("timeout must be positive") {
        shouldThrow<IllegalArgumentException> {
            ClientSelectionResponseGate(FakeMonotonicTimeSource(), pendingTimeoutNanos = 0L)
        }
    }
})
