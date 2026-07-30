package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
class TokenBucketTest : FunSpec({
    test("initial burst is bounded by capacity") {
        val bucket = TokenBucket(capacity = 3, refillTokensPerSecond = 2, FakeMonotonicTimeSource())

        bucket.tryConsume() shouldBe true
        bucket.tryConsume() shouldBe true
        bucket.tryConsume() shouldBe true
        bucket.tryConsume() shouldBe false
        bucket.availableWholeTokens() shouldBe 0
    }

    test("one token refills at the exact half second boundary") {
        val time = FakeMonotonicTimeSource()
        val bucket = TokenBucket(capacity = 3, refillTokensPerSecond = 2, time)

        repeat(3) { bucket.tryConsume() }
        time.advanceBy(499.milliseconds)
        bucket.tryConsume() shouldBe false
        time.advanceBy(1.milliseconds)
        bucket.tryConsume() shouldBe true
        bucket.tryConsume() shouldBe false
    }

    test("refill never exceeds capacity") {
        val time = FakeMonotonicTimeSource()
        val bucket = TokenBucket(capacity = 3, refillTokensPerSecond = 2, time)

        bucket.tryConsume()
        time.advanceBy(10_000.milliseconds)

        bucket.availableWholeTokens() shouldBe 3
    }

    test("non-monotonic time fails fast") {
        val time = FakeMonotonicTimeSource()
        val bucket = TokenBucket(capacity = 3, refillTokensPerSecond = 2, time)
        time.rewindBy(1.milliseconds)

        shouldThrow<IllegalStateException> {
            bucket.tryConsume()
        }
    }

    test("refund restores one consumed token without exceeding capacity") {
        val bucket = TokenBucket(capacity = 2, refillTokensPerSecond = 1, FakeMonotonicTimeSource())

        bucket.tryConsume() shouldBe true
        bucket.refundOne()
        bucket.availableWholeTokens() shouldBe 2
        bucket.refundOne()
        bucket.availableWholeTokens() shouldBe 2
    }

    test("retained capacity preserves a whole token") {
        val bucket = TokenBucket(capacity = 3, refillTokensPerSecond = 1, FakeMonotonicTimeSource())

        bucket.tryConsumeRetaining(1) shouldBe true
        bucket.tryConsumeRetaining(1) shouldBe true
        bucket.tryConsumeRetaining(1) shouldBe false
        bucket.availableWholeTokens() shouldBe 1
    }

    test("reset restores full capacity") {
        val bucket = TokenBucket(capacity = 2, refillTokensPerSecond = 1, FakeMonotonicTimeSource())
        bucket.tryConsume()
        bucket.tryConsume()

        bucket.reset()

        bucket.availableWholeTokens() shouldBe 2
    }

    test("reconfiguration preserves consumed capacity and uses the new refill rate") {
        val time = FakeMonotonicTimeSource()
        val bucket = TokenBucket(capacity = 4, refillTokensPerSecond = 1, time)
        repeat(3) { bucket.tryConsume() }

        bucket.reconfigure(capacity = 2, refillTokensPerSecond = 4)

        bucket.availableWholeTokens() shouldBe 1
        time.advanceBy(250.milliseconds)
        bucket.availableWholeTokens() shouldBe 2
    }

    test("increasing capacity does not mint tokens during reconfiguration") {
        val bucket = TokenBucket(capacity = 1, refillTokensPerSecond = 1, FakeMonotonicTimeSource())
        bucket.tryConsume()

        bucket.reconfigure(capacity = 8, refillTokensPerSecond = 8)

        bucket.availableWholeTokens() shouldBe 0
    }
})
