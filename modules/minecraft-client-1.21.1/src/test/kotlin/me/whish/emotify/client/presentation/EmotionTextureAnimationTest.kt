package me.whish.emotify.client.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion

@Suppress("unused")
class EmotionTextureAnimationTest : FunSpec({
    test("variable frame durations loop without allocating timeline state") {
        val first = EmotionSpriteRegion(0, 0, 16, 16, 48, 16)
        val second = EmotionSpriteRegion(16, 0, 16, 16, 48, 16)
        val third = EmotionSpriteRegion(32, 0, 16, 16, 48, 16)
        val animation = EmotionTextureAnimation(
            listOf(
                EmotionTextureFrame(first, 67),
                EmotionTextureFrame(second, 133),
                EmotionTextureFrame(third, 200),
            ),
        )

        animation.cycleDurationMillis shouldBe 400
        animation.regionAt(0) shouldBe first
        animation.regionAt(66) shouldBe first
        animation.regionAt(67) shouldBe second
        animation.regionAt(199) shouldBe second
        animation.regionAt(200) shouldBe third
        animation.regionAt(399) shouldBe third
        animation.regionAt(400) shouldBe first
        animation.regionAt(-1) shouldBe third
    }
})
