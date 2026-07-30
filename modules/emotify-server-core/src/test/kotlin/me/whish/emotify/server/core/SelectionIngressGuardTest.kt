package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class SelectionIngressGuardTest : FunSpec({
    val emotionId = EmotionId.of("emotify:happy")
    val catalog = EmotionCatalog.of(listOf(emotionId))

    test("one thousand immediate requests admit only the initial burst") {
        val guard = SelectionIngressGuard(FakeMonotonicTimeSource())
        var admitted = 0

        repeat(1_000) {
            if (guard.tryAdmit()) {
                admitted++
            }
        }

        admitted shouldBe 3
    }

    test("ingress refills at two requests per second") {
        val time = FakeMonotonicTimeSource()
        val guard = SelectionIngressGuard(time)
        repeat(3) { guard.tryAdmit() shouldBe true }
        guard.tryAdmit() shouldBe false

        time.advanceBy(499.milliseconds)
        guard.tryAdmit() shouldBe false
        time.advanceBy(1.milliseconds)
        guard.tryAdmit() shouldBe true
    }

    test("ten thousand known selections forward only the initial burst") {
        val guard = SelectionIngressGuard(FakeMonotonicTimeSource())
        var forwarded = 0

        repeat(10_000) {
            if (guard.shouldForward(emotionId, catalog)) {
                forwarded++
            }
        }

        forwarded shouldBe 3
    }

    test("unknown selections never reach gameplay validation") {
        val guard = SelectionIngressGuard(FakeMonotonicTimeSource())
        val unknown = EmotionId.of("external:unknown")
        var forwarded = 0

        repeat(1_000) {
            if (guard.shouldForward(unknown, catalog)) {
                forwarded++
            }
        }

        forwarded shouldBe 0
    }

    test("only one selection can wait for the server main thread") {
        val guard = SelectionIngressGuard(FakeMonotonicTimeSource())

        guard.tryReserveMainThreadTask(emotionId, catalog) shouldBe true
        guard.tryReserveMainThreadTask(emotionId, catalog) shouldBe false

        guard.releaseMainThreadTask()
        guard.tryReserveMainThreadTask(emotionId, catalog) shouldBe true
    }

    test("unknown selection never occupies the main thread slot") {
        val guard = SelectionIngressGuard(FakeMonotonicTimeSource())

        guard.tryReserveMainThreadTask(
            EmotionId.of("external:unknown"),
            catalog,
        ) shouldBe false
        guard.tryReserveMainThreadTask(emotionId, catalog) shouldBe true
    }
})
