package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class CooldownGateTest : FunSpec({
    test("cooldown is ready initially and closes on acquisition") {
        val time = FakeMonotonicTimeSource()
        val cooldown = CooldownGate(1_200.milliseconds, time)

        cooldown.tryAcquire() shouldBe true
        cooldown.tryAcquire() shouldBe false
        cooldown.remaining().inWholeMilliseconds shouldBe 1_200
    }

    test("cooldown opens at the exact deadline") {
        val time = FakeMonotonicTimeSource()
        val cooldown = CooldownGate(1_200.milliseconds, time)

        cooldown.tryAcquire() shouldBe true
        time.advanceBy(1_199.milliseconds)
        cooldown.tryAcquire() shouldBe false
        cooldown.remaining().inWholeMilliseconds shouldBe 1
        time.advanceBy(1.milliseconds)
        cooldown.tryAcquire() shouldBe true
    }

    test("querying remaining time does not consume readiness") {
        val cooldown = CooldownGate(1_200.milliseconds, FakeMonotonicTimeSource())

        cooldown.remaining() shouldBe 0.milliseconds
        cooldown.tryAcquire() shouldBe true
    }

    test("non-monotonic time fails fast") {
        val time = FakeMonotonicTimeSource()
        val cooldown = CooldownGate(1_200.milliseconds, time)

        cooldown.tryAcquire()
        time.rewindBy(1.milliseconds)

        shouldThrow<IllegalStateException> {
            cooldown.remaining()
        }
    }
})
