package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope

@Suppress("unused")
class ClientServerHelloIngressGuardTest : FunSpec({
    test("the initial valid envelope is always admitted") {
        val guard = ClientServerHelloIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)

        guard.tryAdmit(1L, validEnvelope()) shouldBe true
    }

    test("the initial duplicate catalog failure is always admitted and becomes terminal") {
        val guard = ClientServerHelloIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)

        guard.tryAdmit(1L, ServerHelloEnvelope.DuplicateEmotionIds) shouldBe true
        guard.tryAdmit(1L, validEnvelope()) shouldBe false
        guard.tryAdmit(1L, ServerHelloEnvelope.DuplicateEmotionIds) shouldBe false
    }

    test("valid refresh spam is bounded by the refresh burst") {
        val guard = ClientServerHelloIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)
        guard.tryAdmit(1L, validEnvelope()) shouldBe true

        repeat(ClientServerHelloIngressGuard.REFRESH_BURST_CAPACITY) {
            guard.tryAdmit(1L, validEnvelope()) shouldBe true
        }
        guard.tryAdmit(1L, validEnvelope()) shouldBe false
    }

    test("valid refresh budget refills at one envelope per second") {
        val time = FakeMonotonicTimeSource()
        val guard = ClientServerHelloIngressGuard(time)
        guard.begin(1L)
        guard.tryAdmit(1L, validEnvelope())
        repeat(ClientServerHelloIngressGuard.REFRESH_BURST_CAPACITY) {
            guard.tryAdmit(1L, validEnvelope())
        }

        time.advanceBy(999.milliseconds)
        guard.tryAdmit(1L, validEnvelope()) shouldBe false
        time.advanceBy(1.milliseconds)
        guard.tryAdmit(1L, validEnvelope()) shouldBe true
    }

    test("a duplicate catalog after refresh spam is admitted once and closes ingress") {
        val guard = ClientServerHelloIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)
        guard.tryAdmit(1L, validEnvelope())
        repeat(ClientServerHelloIngressGuard.REFRESH_BURST_CAPACITY) {
            guard.tryAdmit(1L, validEnvelope())
        }
        guard.tryAdmit(1L, validEnvelope()) shouldBe false

        guard.tryAdmit(1L, ServerHelloEnvelope.DuplicateEmotionIds) shouldBe true
        guard.tryAdmit(1L, validEnvelope()) shouldBe false
    }

    test("login and logout reset ingress without admitting stale connections") {
        val guard = ClientServerHelloIngressGuard(FakeMonotonicTimeSource())
        guard.begin(1L)
        guard.tryAdmit(1L, ServerHelloEnvelope.DuplicateEmotionIds)
        guard.disconnect(1L)

        guard.tryAdmit(1L, validEnvelope()) shouldBe false
        guard.begin(2L)
        guard.tryAdmit(1L, validEnvelope()) shouldBe false
        guard.tryAdmit(2L, validEnvelope()) shouldBe true
    }
})

private fun validEnvelope(): ServerHelloEnvelope = ServerHelloEnvelope.Valid(
    ServerHello(
        ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE),
        2_200,
        EmotionCatalog.of(emptyList()),
    ),
)
