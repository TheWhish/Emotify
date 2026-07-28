package me.whish.emotify.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudiencePolicyTest : FunSpec({
    test("audience requires tracking negotiation visibility dimension and radius") {
        val eligible = AudienceCandidate(
            tracking = true,
            negotiated = true,
            visible = true,
            sameDimension = true,
            distanceSquared = 4_096.0,
        )

        AudiencePolicy.isEligible(eligible) shouldBe true
        AudiencePolicy.isEligible(eligible.copy(tracking = false)) shouldBe false
        AudiencePolicy.isEligible(eligible.copy(negotiated = false)) shouldBe false
        AudiencePolicy.isEligible(eligible.copy(visible = false)) shouldBe false
        AudiencePolicy.isEligible(eligible.copy(sameDimension = false)) shouldBe false
        AudiencePolicy.isEligible(eligible.copy(distanceSquared = 4_096.000_001)) shouldBe false
    }

    test("sender is admitted without tracking but still requires every other predicate") {
        val self = AudienceCandidate(
            tracking = false,
            negotiated = true,
            visible = true,
            sameDimension = true,
            distanceSquared = 0.0,
            self = true,
        )

        AudiencePolicy.isEligible(self) shouldBe true
        AudiencePolicy.isEligible(self.copy(negotiated = false)) shouldBe false
    }

    test("fan out traversal has a hard candidate boundary") {
        AudiencePolicy.canVisitCandidate(0) shouldBe true
        AudiencePolicy.canVisitCandidate(AudiencePolicy.MAX_TRACKING_CANDIDATES - 1) shouldBe true
        AudiencePolicy.canVisitCandidate(AudiencePolicy.MAX_TRACKING_CANDIDATES) shouldBe false
    }
})
