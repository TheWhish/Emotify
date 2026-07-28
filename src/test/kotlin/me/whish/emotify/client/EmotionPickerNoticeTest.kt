package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

class EmotionPickerNoticeTest : FunSpec({
    test("notice fades in holds and fades out") {
        EmotionPickerNoticeAnimation.opacityAt(0.0, 0.0) shouldBe 0.0
        EmotionPickerNoticeAnimation.opacityAt(80.0, 80.0) shouldBe 0.5
        EmotionPickerNoticeAnimation.opacityAt(160.0, 160.0) shouldBe 1.0
        EmotionPickerNoticeAnimation.opacityAt(1_500.0, 1_500.0) shouldBe 1.0

        val fading = EmotionPickerNoticeAnimation.opacityAt(2_125.0, 2_125.0)
        fading shouldBeGreaterThan 0.0
        fading shouldBeLessThan 1.0

        EmotionPickerNoticeAnimation.opacityAt(2_350.0, 2_350.0) shouldBe 0.0
        EmotionPickerNoticeAnimation.isFinished(2_349.0) shouldBe false
        EmotionPickerNoticeAnimation.isFinished(2_350.0) shouldBe true
    }

    test("font unsafe alpha values are suppressed instead of becoming opaque") {
        EmotionPickerNoticeAnimation.renderAlpha(0.0) shouldBe 0
        EmotionPickerNoticeAnimation.renderAlpha(1.0 / 255.0) shouldBe 0
        EmotionPickerNoticeAnimation.renderAlpha(3.0 / 255.0) shouldBe 0
        EmotionPickerNoticeAnimation.renderAlpha(4.0 / 255.0) shouldBe 4
        EmotionPickerNoticeAnimation.renderAlpha(1.0) shouldBe 255
    }

    test("repeated message extends its lifetime without restarting fade in") {
        val first = EmotionPickerNotice.show(null, "wait", 0L)
        val refreshed = EmotionPickerNotice.show(first, "wait", 1_500_000_000L)

        refreshed.firstShownAtNanos shouldBe first.firstShownAtNanos
        refreshed.lastShownAtNanos shouldBe 1_500_000_000L
        refreshed.opacityAt(1_600_000_000L) shouldBe 1.0
        refreshed.isFinished(3_849_000_000L) shouldBe false
        refreshed.isFinished(3_850_000_000L) shouldBe true
    }

    test("different message starts a fresh notification") {
        val first = EmotionPickerNotice.show(null, "wait", 0L)
        val replacement = EmotionPickerNotice.show(first, "unavailable", 2_000_000_000L)

        replacement.message shouldBe "unavailable"
        replacement.firstShownAtNanos shouldBe 2_000_000_000L
        replacement.opacityAt(2_000_000_000L) shouldBe 0.0
    }

    test("notice is centered below the panel using the shared visual gap") {
        val bounds = EmotionPickerNoticeLayout.bounds(
            screenHeight = 360,
            panelX = 27,
            panelY = 67,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 180,
            lineHeight = 9,
        )

        bounds.width shouldBe 192
        bounds.height shouldBe 13
        bounds.x shouldBe 54
        bounds.y shouldBe 297
        bounds.y - (67 + 226) shouldBe EmotionPickerVisualMetrics.GAP
    }

    test("notice width stays inside the panel and constrained screens keep it visible") {
        val bounds = EmotionPickerNoticeLayout.bounds(
            screenHeight = 180,
            panelX = 10,
            panelY = 10,
            panelWidth = 180,
            panelHeight = 160,
            textWidth = 500,
            lineHeight = 9,
        )

        bounds.x shouldBe 16
        bounds.width shouldBe 168
        bounds.y shouldBe 163
        EmotionPickerNoticeLayout.maximumTextWidth(180) shouldBe 156
    }
})
