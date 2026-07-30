package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class AudiencePolicyTest : FunSpec({
    test("audience requires tracking negotiation visibility dimension and radius") {
        AudiencePolicy.isEligible(true, true, true, true, 4_096.0) shouldBe true
        AudiencePolicy.isEligible(false, true, true, true, 1.0) shouldBe false
        AudiencePolicy.isEligible(true, false, true, true, 1.0) shouldBe false
        AudiencePolicy.isEligible(true, true, false, true, 1.0) shouldBe false
        AudiencePolicy.isEligible(true, true, true, false, 1.0) shouldBe false
        AudiencePolicy.isEligible(true, true, true, true, -0.1) shouldBe false
        AudiencePolicy.isEligible(true, true, true, true, 4_096.000_001) shouldBe false
    }

    test("self bypasses tracking but no safety predicate") {
        AudiencePolicy.isEligible(false, true, true, true, 0.0, self = true) shouldBe true
        AudiencePolicy.isEligible(false, false, true, true, 0.0, self = true) shouldBe false
        AudiencePolicy.isEligible(false, true, false, true, 0.0, self = true) shouldBe false
        AudiencePolicy.isEligible(false, true, true, false, 0.0, self = true) shouldBe false
    }

    test("fanout boundary is a fixed scalar constant") {
        AudiencePolicy.MAX_TRACKING_CANDIDATES shouldBe 256
        AudiencePolicy.MAX_DISTANCE_SQUARED shouldBe 4_096.0
    }

    test("runtime policy can only tighten compiled audience ceilings") {
        val policy = ServerAudiencePolicy(radius = 24.0, maximumTrackingCandidates = 64)

        policy.maximumDistanceSquared shouldBe 576.0
        AudiencePolicy.isEligible(policy, true, true, true, true, 576.0) shouldBe true
        AudiencePolicy.isEligible(policy, true, true, true, true, 576.000_001) shouldBe false
    }
})
