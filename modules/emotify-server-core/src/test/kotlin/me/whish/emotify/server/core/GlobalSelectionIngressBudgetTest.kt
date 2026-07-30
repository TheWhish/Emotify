package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class GlobalSelectionIngressBudgetTest : FunSpec({
    test("outstanding capacity is released exactly once without refunding request rate") {
        val budget = GlobalSelectionIngressBudget(
            maxOutstanding = 1,
            requestBurstCapacity = 1,
            requestRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )
        val lease = budget.tryAcquire()
            .shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
            .lease

        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.OutstandingLimitReached
        lease.release() shouldBe GlobalSelectionIngressRelease.RELEASED
        lease.release() shouldBe GlobalSelectionIngressRelease.ALREADY_RELEASED
        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.RateLimited
        budget.snapshot() shouldBe GlobalSelectionIngressSnapshot(0, 0)
    }

    test("request token bucket refills at its configured rate") {
        val time = FakeMonotonicTimeSource()
        val budget = GlobalSelectionIngressBudget(
            maxOutstanding = 4,
            requestBurstCapacity = 1,
            requestRefillTokensPerSecond = 2,
            timeSource = time,
        )
        val first = budget.tryAcquire()
            .shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
            .lease
        first.release()

        time.advanceBy(499.milliseconds)
        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.RateLimited
        time.advanceBy(1.milliseconds)
        budget.tryAcquire().shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
    }

    test("reset invalidates old leases and restores all capacity") {
        val budget = GlobalSelectionIngressBudget(
            maxOutstanding = 2,
            requestBurstCapacity = 2,
            requestRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )
        val oldLease = budget.tryAcquire()
            .shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
            .lease

        budget.reset()

        budget.snapshot() shouldBe GlobalSelectionIngressSnapshot(0, 2)
        oldLease.release() shouldBe GlobalSelectionIngressRelease.STALE_AFTER_RESET
        oldLease.release() shouldBe GlobalSelectionIngressRelease.ALREADY_RELEASED
        budget.snapshot() shouldBe GlobalSelectionIngressSnapshot(0, 2)
        repeat(2) {
            budget.tryAcquire().shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
        }
        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.OutstandingLimitReached
    }

    test("parallel admissions never exceed the global outstanding ceiling") {
        val maxOutstanding = 73
        val workers = 16
        val attemptsPerWorker = 100
        val budget = GlobalSelectionIngressBudget(
            maxOutstanding = maxOutstanding,
            requestBurstCapacity = workers * attemptsPerWorker,
            requestRefillTokensPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )
        val leases = ConcurrentLinkedQueue<GlobalSelectionIngressLease>()
        val rejectedOutstanding = AtomicInteger()
        val rejectedRate = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val futures = List(workers) {
                executor.submit {
                    start.await()
                    repeat(attemptsPerWorker) {
                        when (val admission = budget.tryAcquire()) {
                            is GlobalSelectionIngressAdmission.Admitted -> leases += admission.lease
                            GlobalSelectionIngressAdmission.OutstandingLimitReached -> rejectedOutstanding.incrementAndGet()
                            GlobalSelectionIngressAdmission.RateLimited -> rejectedRate.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        leases.size shouldBe maxOutstanding
        rejectedOutstanding.get() shouldBe workers * attemptsPerWorker - maxOutstanding
        rejectedRate.get() shouldBe 0
        budget.snapshot().outstanding shouldBe maxOutstanding
        leases.forEach { it.release() shouldBe GlobalSelectionIngressRelease.RELEASED }
        budget.snapshot().outstanding shouldBe 0
    }

    test("reconfiguration preserves outstanding leases and consumed request tokens") {
        val time = FakeMonotonicTimeSource()
        val budget = GlobalSelectionIngressBudget(
            maxOutstanding = 4,
            requestBurstCapacity = 4,
            requestRefillTokensPerSecond = 1,
            timeSource = time,
        )
        val first = budget.tryAcquire().shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>().lease
        val second = budget.tryAcquire().shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>().lease

        budget.reconfigure(
            GlobalSelectionIngressLimits(
                maximumOutstanding = 1,
                requestBurstCapacity = 2,
                requestRefillTokensPerSecond = 4,
            ),
        )

        budget.snapshot() shouldBe GlobalSelectionIngressSnapshot(2, 2)
        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.OutstandingLimitReached
        first.release() shouldBe GlobalSelectionIngressRelease.RELEASED
        budget.tryAcquire() shouldBe GlobalSelectionIngressAdmission.OutstandingLimitReached
        second.release() shouldBe GlobalSelectionIngressRelease.RELEASED
        budget.tryAcquire().shouldBeInstanceOf<GlobalSelectionIngressAdmission.Admitted>()
    }
})
