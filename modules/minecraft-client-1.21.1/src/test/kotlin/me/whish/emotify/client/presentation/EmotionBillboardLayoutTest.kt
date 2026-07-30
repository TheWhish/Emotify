package me.whish.emotify.client.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionBillboardLayoutTest : FunSpec({
    test("upright icon stays close to the standing head") {
        EmotionBillboardLayout.localY(1.8, 0.0, EmotionBillboardPose.UPRIGHT) shouldBe
            (2.07 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.bottomClearance(EmotionBillboardPose.UPRIGHT) shouldBe
            (0.0 plusOrMinus 1.0e-8)
    }

    test("renderer offset is cancelled instead of moving the icon with crouch") {
        val standingWorldY = EmotionBillboardLayout.localY(1.8, 0.0, EmotionBillboardPose.UPRIGHT)
        val crouchingRenderOffset = -0.125
        val crouchingWorldY =
            crouchingRenderOffset +
                EmotionBillboardLayout.localY(1.8, crouchingRenderOffset, EmotionBillboardPose.UPRIGHT)

        crouchingWorldY.shouldBeExactly(standingWorldY)
    }

    test("prone poses use their compact visual dimensions without interpolation") {
        EmotionBillboardLayout.localY(0.6, 0.0, EmotionBillboardPose.PRONE) shouldBe
            (1.07 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.bottomClearance(EmotionBillboardPose.PRONE) shouldBe
            (0.20 plusOrMinus 1.0e-8)
    }

    test("sleeping pose uses a dedicated bed-safe anchor") {
        EmotionBillboardLayout.localY(0.2, 0.0, EmotionBillboardPose.SLEEPING) shouldBe
            (0.87 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.bottomClearance(EmotionBillboardPose.SLEEPING) shouldBe
            (0.40 plusOrMinus 1.0e-8)
    }

    test("horizontal renderer offsets are cancelled on both axes") {
        EmotionBillboardLayout.localX(0.25).shouldBeExactly(-0.25)
        EmotionBillboardLayout.localZ(-0.4).shouldBeExactly(0.4)
    }

    test("fall flying anchor follows the vanilla rotation transition") {
        EmotionBillboardLayout.fallFlyingLocalY(2.07, 1.07, 0, 0.0f) shouldBe
            (2.07 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.fallFlyingLocalY(2.07, 1.07, 5, 0.0f) shouldBe
            (1.82 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.fallFlyingLocalY(2.07, 1.07, 10, 0.0f) shouldBe
            (1.07 plusOrMinus 1.0e-12)
    }

    test("fall flying transition clamps malformed frame inputs") {
        EmotionBillboardLayout.fallFlyingLocalY(2.07, 1.07, -5, -1.0f) shouldBe
            (2.07 plusOrMinus 1.0e-12)
        EmotionBillboardLayout.fallFlyingLocalY(2.07, 1.07, 20, 2.0f) shouldBe
            (1.07 plusOrMinus 1.0e-12)
    }
})
