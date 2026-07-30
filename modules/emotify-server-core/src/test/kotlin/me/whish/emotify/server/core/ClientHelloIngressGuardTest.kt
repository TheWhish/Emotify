package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello

@Suppress("unused")
class ClientHelloIngressGuardTest : FunSpec({
    val initial = ClientHello(ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE))

    test("ten thousand identical hellos produce one main thread admission") {
        val guard = ClientHelloIngressGuard()
        var admitted = 0

        repeat(10_000) {
            if (guard.evaluate(initial).shouldForward) {
                admitted++
            }
        }

        admitted shouldBe 1
    }

    test("one changed repeat is admitted and all later packets are blocked") {
        val guard = ClientHelloIngressGuard()
        val changed = ClientHello(
            ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags.NONE),
        )

        guard.evaluate(initial) shouldBe ClientHelloIngressDecision.FORWARD_INITIAL
        guard.evaluate(changed) shouldBe ClientHelloIngressDecision.FORWARD_CHANGED
        guard.evaluate(changed) shouldBe ClientHelloIngressDecision.DROP_BLOCKED
        guard.evaluate(initial) shouldBe ClientHelloIngressDecision.DROP_BLOCKED
    }

    test("ten thousand mixed hellos admit at most two main thread tasks") {
        val guard = ClientHelloIngressGuard()
        val changed = ClientHello(
            ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags.NONE),
        )
        var admitted = 0

        repeat(10_000) { index ->
            val hello = if (index % 2 == 0) initial else changed
            if (guard.evaluate(hello).shouldForward) {
                admitted++
            }
        }

        admitted shouldBe 2
    }
})
