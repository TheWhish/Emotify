package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.nanoseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class ClientPlayIngressGuardTest : FunSpec({
    test("packets later rejected by semantic validation still consume the cheap ingress burst") {
        val guard = ClientPlayIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)

        repeat(ClientPlayIngressGuard.PLAY_BURST_CAPACITY) {
            guard.tryAdmit(1L) shouldBe true
        }
        guard.tryAdmit(1L) shouldBe false
    }

    test("play ingress refills at sixteen packets per second") {
        val time = FakeMonotonicTimeSource()
        val guard = ClientPlayIngressGuard(time)
        guard.begin(1L)
        repeat(ClientPlayIngressGuard.PLAY_BURST_CAPACITY) {
            guard.tryAdmit(1L)
        }

        time.advanceBy(62_499_999.nanoseconds)
        guard.tryAdmit(1L) shouldBe false
        time.advanceBy(1.nanoseconds)
        guard.tryAdmit(1L) shouldBe true
    }

    test("login and logout reset play budget without admitting stale connections") {
        val guard = ClientPlayIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)
        repeat(ClientPlayIngressGuard.PLAY_BURST_CAPACITY) {
            guard.tryAdmit(1L)
        }
        guard.disconnect(1L)

        guard.tryAdmit(1L) shouldBe false
        guard.begin(2L)
        guard.tryAdmit(1L) shouldBe false
        repeat(ClientPlayIngressGuard.PLAY_BURST_CAPACITY) {
            guard.tryAdmit(2L) shouldBe true
        }
        guard.tryAdmit(2L) shouldBe false
    }
})
