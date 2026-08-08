package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.FakeMonotonicTimeSource
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
class CustomAssetIngressBudgetTest : FunSpec({
    test("bounds aggregate partial upload bytes and releases completed transfers") {
        val budget = CustomAssetIngressBudget(maximumRetainedBytes = 2_000_000)
        val first = budget.tryAcquire(400_000).shouldNotBeNull()

        budget.tryAcquire(400_000).shouldBeNull()
        budget.retainedBytes() shouldBe 1_200_000L

        first.close()
        budget.retainedBytes() shouldBe 0L
        budget.tryAcquire(400_000).shouldNotBeNull()
    }

    test("expires abandoned upload leases before admitting a later transfer") {
        val time = FakeMonotonicTimeSource()
        val budget = CustomAssetIngressBudget(maximumRetainedBytes = 2_000_000, timeSource = time)
        var expired = 0

        budget.tryAcquire(400_000) { expired += 1 }.shouldNotBeNull()
        time.advanceBy(11.seconds)

        budget.tryAcquire(400_000).shouldNotBeNull()
        expired shouldBe 1
        budget.retainedBytes() shouldBe 1_200_000L
    }
})
