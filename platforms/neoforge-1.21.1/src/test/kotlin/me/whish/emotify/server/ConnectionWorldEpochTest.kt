package me.whish.emotify.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("unused")
class ConnectionWorldEpochTest : FunSpec({
    test("epoch begins positive and advances monotonically") {
        val epoch = ConnectionWorldEpoch()

        epoch.current() shouldBe 1L
        epoch.advance() shouldBe 2L
        epoch.current() shouldBe 2L
    }

    test("parallel lifecycle transitions cannot lose increments") {
        val epoch = ConnectionWorldEpoch()
        val workers = 8
        val advancesPerWorker = 1_000
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val futures = List(workers) {
                executor.submit {
                    start.await()
                    repeat(advancesPerWorker) { epoch.advance() }
                }
            }
            start.countDown()
            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        epoch.current() shouldBe 1L + workers * advancesPerWorker
    }

    test("invalid initial values and overflow fail fast") {
        shouldThrow<IllegalArgumentException> { ConnectionWorldEpoch(0L) }
        val exhausted = ConnectionWorldEpoch(Long.MAX_VALUE)

        shouldThrow<IllegalStateException> { exhausted.advance() }
        exhausted.current() shouldBe Long.MAX_VALUE
    }
})
