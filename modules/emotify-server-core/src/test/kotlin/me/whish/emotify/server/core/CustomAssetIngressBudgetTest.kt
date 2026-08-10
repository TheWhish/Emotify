package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.wire.v1.CustomEmojiLosslessEncoding
import me.whish.emotify.wire.v1.CustomEmojiLosslessPreflight
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
class CustomAssetIngressBudgetTest : FunSpec({
    val preflight = CustomEmojiLosslessPreflight(
        size = 64,
        frameCount = 30,
        rawBytes = 491_520,
        frameBytes = 16_384,
        encodedBytes = 400_000,
        encoding = CustomEmojiLosslessEncoding.DEFLATE,
    )

    test("bounds aggregate partial upload bytes and releases completed transfers") {
        val budget = CustomAssetIngressBudget(maximumRetainedBytes = 3_000_000)
        val first = budget.tryAcquire(preflight).shouldNotBeNull()

        budget.tryAcquire(preflight).shouldBeNull()
        budget.retainedBytes() shouldBe 2_264_960L

        first.close()
        budget.retainedBytes() shouldBe 0L
        budget.tryAcquire(preflight).shouldNotBeNull()
    }

    test("expires abandoned upload leases before admitting a later transfer") {
        val time = FakeMonotonicTimeSource()
        val budget = CustomAssetIngressBudget(maximumRetainedBytes = 3_000_000, timeSource = time)
        var expired = 0

        budget.tryAcquire(preflight) { expired += 1 }.shouldNotBeNull()
        time.advanceBy(11.seconds)

        budget.tryAcquire(preflight).shouldNotBeNull()
        expired shouldBe 1
        budget.retainedBytes() shouldBe 2_264_960L
    }

    test("compressed payload reservation includes its decoded working set") {
        val compressed = preflight.copy(
            rawBytes = 491_520,
            frameBytes = 16_384,
            encodedBytes = 2_048,
            encoding = CustomEmojiLosslessEncoding.DEFLATE,
        )

        val lease = CustomAssetIngressBudget().tryAcquire(compressed).shouldNotBeNull()

        lease.byteCount shouldBe 1_071_104L
        (lease.byteCount > compressed.encodedBytes * 100L) shouldBe true
    }
})
