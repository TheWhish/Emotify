package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class AudienceBudgetTest : FunSpec({
    test("global and region reservations are atomic and refundable") {
        val budget = AudienceBudget(
            globalCapacity = 2,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )

        budget.tryReserve(1, 10L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 10L) shouldBe AudienceReservation.REGION_BUSY
        budget.tryReserve(1, 11L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 12L) shouldBe AudienceReservation.GLOBAL_BUSY
        budget.refund(1, 11L)
        budget.tryReserve(1, 12L) shouldBe AudienceReservation.RESERVED
    }

    test("equal region keys in different dimensions have independent capacity") {
        val budget = AudienceBudget(
            globalCapacity = 2,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )

        budget.tryReserve(1, 5L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(2, 5L) shouldBe AudienceReservation.RESERVED
        budget.trackedRegionCount shouldBe 2
    }

    test("failed regional consumes do not pin an exhausted region") {
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
        time.advanceBy(51.milliseconds)
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.RESERVED
        budget.trackedRegionCount shouldBe 1
    }

    test("region limit rejects new keys until expiry") {
        val time = FakeMonotonicTimeSource()
        val budget = AudienceBudget(
            globalCapacity = 8,
            globalRefillTokensPerSecond = 8,
            regionCapacity = 2,
            regionRefillTokensPerSecond = 1,
            regionIdleTtl = 100.milliseconds,
            sweepInterval = 10.milliseconds,
            maxRegions = 1,
            timeSource = time,
        )

        budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.REGION_BUSY
        time.advanceBy(101.milliseconds)
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.RESERVED
    }

    test("clear restores process capacity and removes regional state") {
        val budget = AudienceBudget(
            globalCapacity = 1,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 1,
            regionRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )
        budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED

        budget.clear()

        budget.trackedRegionCount shouldBe 0
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.RESERVED
    }

    test("reconfiguration preserves consumed global and regional tokens") {
        val time = FakeMonotonicTimeSource()
        val budget = AudienceBudget(
            globalCapacity = 8,
            globalRefillTokensPerSecond = 1,
            regionCapacity = 4,
            regionRefillTokensPerSecond = 1,
            timeSource = time,
        )
        repeat(4) { budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED }

        budget.reconfigure(
            AudienceBudgetLimits(
                globalCapacity = 2,
                globalRefillTokensPerSecond = 4,
                regionCapacity = 2,
                regionRefillTokensPerSecond = 4,
                maximumRegions = 1,
            ),
        )

        budget.tryReserve(1, 1L) shouldBe AudienceReservation.REGION_BUSY
        time.advanceBy(250.milliseconds)
        budget.tryReserve(1, 1L) shouldBe AudienceReservation.RESERVED
        budget.tryReserve(1, 2L) shouldBe AudienceReservation.REGION_BUSY
    }
})
