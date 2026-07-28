package me.whish.emotify.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

class AudienceBudgetTest : FunSpec({
    test("global and region reservations are atomic and refundable") {
        val time = FakeMonotonicTimeSource()
        val budget = AudienceBudget(
            globalCapacity = 2,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = time,
        )

        budget.tryReserve(1, 10L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 10L) shouldBe AudienceReservation.REGION_BUSY
        budget.tryReserve(1, 11L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 12L) shouldBe AudienceReservation.GLOBAL_BUSY
        budget.refund(1, 11L)
        budget.tryReserve(1, 12L) shouldBe AudienceReservation.RESERVED
    }

    test("same region key in different dimensions has independent capacity") {
        val budget = AudienceBudget(
            globalCapacity = 4,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )

        budget.tryReserve(1, 5L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(2, 5L) shouldBe AudienceReservation.RESERVED
    }

    test("expired regions are removed opportunistically while recently touched exhaustion stays") {
        val time = FakeMonotonicTimeSource()
        val budget = AudienceBudget(
            globalCapacity = 8,
            globalRefillTokensPerSecond = 8,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            regionIdleTtl = 100.milliseconds,
            sweepInterval = 10.milliseconds,
            maxRegions = 1,
            timeSource = time,
        )

        budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
        time.advanceBy(50.milliseconds)
        budget.tryReserve(1, 1L) shouldBe AudienceReservation.REGION_BUSY
        time.advanceBy(60.milliseconds)
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.REGION_BUSY
        time.advanceBy(41.milliseconds)
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.RESERVED
    }

    test("clear resets global and regional process state") {
        val budget = AudienceBudget(
            globalCapacity = 1,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )
        budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.GLOBAL_BUSY

        budget.clear()

        budget.tryReserve(1, 2L) shouldBe AudienceReservation.RESERVED
    }
})
