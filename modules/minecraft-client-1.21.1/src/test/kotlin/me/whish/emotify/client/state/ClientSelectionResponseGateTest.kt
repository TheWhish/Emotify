package me.whish.emotify.client.state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class ClientSelectionResponseGateTest : FunSpec({
    val happy = EmotionId.of("emotify:happy")
    val sad = EmotionId.of("emotify:sad")

    test("only one selection can wait for a response") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())

        gate.tryReserve(happy) shouldBe true
        gate.tryReserve(sad) shouldBe false
    }

    test("successful self play releases the pending selection") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve(happy)

        gate.tryConsumeSuccess(happy) shouldBe true
        gate.tryReserve(sad) shouldBe true
    }

    test("accepted hidden self play releases pending state before presentation") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve(happy)

        gate.tryConsumeAcceptedPlay(happy, localSource = true, ClientEmotionPlayDisposition.HIDDEN) shouldBe true
        gate.tryReserve(sad) shouldBe true
    }

    test("remote or rejected play cannot acknowledge a local request") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve(happy)

        gate.tryConsumeAcceptedPlay(happy, localSource = false, ClientEmotionPlayDisposition.VISIBLE) shouldBe false
        gate.tryConsumeAcceptedPlay(happy, localSource = true, ClientEmotionPlayDisposition.REJECTED) shouldBe false
        gate.tryReserve(sad) shouldBe false
    }

    test("late success for a different emotion cannot consume the current request") {
        val time = FakeMonotonicTimeSource()
        val gate = ClientSelectionResponseGate(time, pendingTimeoutNanos = 500.milliseconds.inWholeNanoseconds)
        gate.tryReserve(happy)
        time.advanceBy(500.milliseconds)
        gate.tryReserve(sad) shouldBe true

        gate.tryConsumeSuccess(happy) shouldBe false
        gate.tryReserve(happy) shouldBe false
        gate.tryConsumeSuccess(sad) shouldBe true
    }

    test("rejection releases the pending selection") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve(happy)

        gate.tryConsumeRejection() shouldBe true
        gate.tryConsumeRejection() shouldBe false
    }

    test("lost responses expire without blocking later selections") {
        val time = FakeMonotonicTimeSource()
        val gate = ClientSelectionResponseGate(time, pendingTimeoutNanos = 500.milliseconds.inWholeNanoseconds)
        gate.tryReserve(happy)

        time.advanceBy(499.milliseconds)
        gate.tryReserve(sad) shouldBe false
        time.advanceBy(1.milliseconds)
        gate.tryReserve(sad) shouldBe true
    }

    test("cancel and reset clear pending state") {
        val gate = ClientSelectionResponseGate(FakeMonotonicTimeSource())
        gate.tryReserve(happy)
        gate.cancelReservation()
        gate.tryReserve(sad) shouldBe true

        gate.reset()
        gate.tryConsumeSuccess(sad) shouldBe false
    }

    test("monotonic clock rollback fails fast") {
        val time = FakeMonotonicTimeSource(1_000L)
        val gate = ClientSelectionResponseGate(time)
        gate.tryReserve(happy)
        time.rewindBy(1.milliseconds)

        shouldThrow<IllegalStateException> {
            gate.tryReserve(sad)
        }
    }

    test("timeout must be positive") {
        shouldThrow<IllegalArgumentException> {
            ClientSelectionResponseGate(FakeMonotonicTimeSource(), pendingTimeoutNanos = 0L)
        }
    }
})
