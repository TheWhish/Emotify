package me.whish.emotify.client.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionHotbarFeedbackMathTest : FunSpec({
    test("returns invisible state before start or after expiration") {
        EmotionHotbarFeedbackMath.evaluate(-1.0).isVisible.shouldBeFalse()
        EmotionHotbarFeedbackMath.evaluate(3000.0).isVisible.shouldBeFalse()
        EmotionHotbarFeedbackMath.evaluate(3500.0).isVisible.shouldBeFalse()
    }

    test("interpolates smooth entry curve without overshoot") {
        val start = EmotionHotbarFeedbackMath.evaluate(0.0)
        start.isVisible.shouldBeTrue()
        start.alpha.toDouble() shouldBe (0.0 plusOrMinus 0.001)
        start.scale.toDouble() shouldBe (0.75 plusOrMinus 0.001)

        val midEntry = EmotionHotbarFeedbackMath.evaluate(175.0)
        midEntry.isVisible.shouldBeTrue()
        (midEntry.alpha > 0.8f).shouldBeTrue()
        (midEntry.scale > 0.95f).shouldBeTrue()
        (midEntry.yOffset > 0.0f).shouldBeTrue()
    }

    test("stays fully opaque and at standard scale during sustain phase") {
        val mid = EmotionHotbarFeedbackMath.evaluate(1500.0)
        mid.isVisible.shouldBeTrue()
        mid.alpha shouldBe 1.0f
        mid.scale shouldBe 1.0f
        mid.yOffset shouldBe 0.0f
    }

    test("fades out and ascends smoothly during exit phase") {
        val fadeOutStart = EmotionHotbarFeedbackMath.evaluate(2550.0)
        fadeOutStart.isVisible.shouldBeTrue()
        fadeOutStart.alpha.toDouble() shouldBe (1.0 plusOrMinus 0.01)

        val lateExit = EmotionHotbarFeedbackMath.evaluate(2900.0)
        lateExit.isVisible.shouldBeTrue()
        (lateExit.alpha < 0.4f).shouldBeTrue()
        (lateExit.yOffset < 0.0f).shouldBeTrue()
    }

    test("computes centered floating point coordinates correctly") {
        EmotionHotbarFeedbackMath.centerX(1920) shouldBe 960.0f
        EmotionHotbarFeedbackMath.centerY(1080, 16, 56) shouldBe 1016.0f
    }
})