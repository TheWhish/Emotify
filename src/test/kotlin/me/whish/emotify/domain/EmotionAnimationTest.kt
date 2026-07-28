package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll

class EmotionAnimationTest : FunSpec({
    test("full animation matches all timeline boundaries") {
        EmotionAnimation.alphaAt(0.0) shouldBe 0.0
        EmotionAnimation.scaleAt(0.0, AnimationMotion.FULL) shouldBe 0.75
        EmotionAnimation.alphaAt(EmotionAnimation.APPEAR_MILLIS) shouldBe 1.0
        EmotionAnimation.scaleAt(EmotionAnimation.APPEAR_MILLIS, AnimationMotion.FULL) shouldBe 1.0
        EmotionAnimation.verticalOffsetAt(EmotionAnimation.HOLD_END_MILLIS, AnimationMotion.FULL) shouldBe 0.0
        EmotionAnimation.alphaAt(EmotionAnimation.DURATION_MILLIS) shouldBe 0.0
        EmotionAnimation.verticalOffsetAt(EmotionAnimation.DURATION_MILLIS, AnimationMotion.FULL) shouldBe
            EmotionAnimation.MAX_RISE_BLOCKS
        EmotionAnimation.isFinished(EmotionAnimation.DURATION_MILLIS) shouldBe true
    }

    test("reduced motion keeps scale and position fixed") {
        checkAll(Arb.double(-5_000.0..10_000.0)) { elapsed ->
            EmotionAnimation.scaleAt(elapsed, AnimationMotion.REDUCED) shouldBe 1.0
            EmotionAnimation.verticalOffsetAt(elapsed, AnimationMotion.REDUCED) shouldBe 0.0
        }
    }

    test("all animation values remain bounded") {
        checkAll(Arb.double(-5_000.0..10_000.0)) { elapsed ->
            val alpha = EmotionAnimation.alphaAt(elapsed)
            val scale = EmotionAnimation.scaleAt(elapsed, AnimationMotion.FULL)
            val rise = EmotionAnimation.verticalOffsetAt(elapsed, AnimationMotion.FULL)

            (alpha in 0.0..1.0) shouldBe true
            (scale in 0.75..1.0) shouldBe true
            (rise in 0.0..EmotionAnimation.MAX_RISE_BLOCKS) shouldBe true
        }
    }

    test("smooth step clamps and keeps endpoints exact") {
        EmotionAnimation.smoothStep(-1.0) shouldBe 0.0
        EmotionAnimation.smoothStep(0.0) shouldBe 0.0
        EmotionAnimation.smoothStep(0.5) shouldBe 0.5
        EmotionAnimation.smoothStep(1.0) shouldBe 1.0
        EmotionAnimation.smoothStep(2.0) shouldBe 1.0
    }

    test("render time conversion is tick independent and rejects a backwards clock") {
        EmotionAnimation.elapsedMillis(1_000_000_000L, 3_200_000_000L) shouldBe 2_200.0
        EmotionAnimation.opacityByteAt(0.0) shouldBe 0
        EmotionAnimation.opacityByteAt(EmotionAnimation.APPEAR_MILLIS) shouldBe 255
        EmotionAnimation.opacityByteAt(EmotionAnimation.DURATION_MILLIS) shouldBe 0

        shouldThrow<IllegalArgumentException> {
            EmotionAnimation.elapsedMillis(2L, 1L)
        }
    }
})
