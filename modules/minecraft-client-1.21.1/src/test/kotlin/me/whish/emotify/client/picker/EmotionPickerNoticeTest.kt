package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

@Suppress("unused")
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
            screenWidth = 300,
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

    test("notice expands beyond the panel to fit a longer message") {
        val bounds = EmotionPickerNoticeLayout.bounds(
            screenWidth = 420,
            screenHeight = 360,
            panelX = 87,
            panelY = 67,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 286,
            lineHeight = 9,
        )

        bounds.x shouldBe 61
        bounds.width shouldBe 298
        bounds.y shouldBe 297
    }

    test("notice width is constrained by the screen") {
        val bounds = EmotionPickerNoticeLayout.bounds(
            screenWidth = 180,
            screenHeight = 180,
            panelX = 0,
            panelY = 10,
            panelWidth = 180,
            panelHeight = 160,
            textWidth = 500,
            lineHeight = 9,
        )

        bounds.x shouldBe 4
        bounds.width shouldBe 172
        bounds.y shouldBe 163
        EmotionPickerNoticeLayout.maximumTextWidth(180) shouldBe 160
    }

    test("notice width has an absolute upper bound on large screens") {
        val bounds = EmotionPickerNoticeLayout.bounds(
            screenWidth = 1_000,
            screenHeight = 500,
            panelX = 377,
            panelY = 100,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 1_000,
            lineHeight = 9,
        )

        bounds.x shouldBe 340
        bounds.width shouldBe 320
        EmotionPickerNoticeLayout.maximumTextWidth(1_000) shouldBe 308
    }

    test("custom hint floats outside the panel without changing its viewport") {
        val bounds = EmotionPickerHintLayout.bounds(
            screenWidth = 300,
            screenHeight = 360,
            panelX = 27,
            panelY = 67,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 160,
            lineHeight = 9,
        )

        bounds.y - (67 + 226) shouldBe EmotionPickerVisualMetrics.GAP
        bounds.width shouldBe 246
        bounds.height shouldBe 15
        bounds.x shouldBe 27
        bounds.closeY - bounds.y shouldBe EmotionPickerHintLayout.VERTICAL_PADDING
        bounds.closeX + bounds.closeSize + EmotionPickerHintLayout.HORIZONTAL_PADDING shouldBe bounds.x + bounds.width
        bounds.containsClose(bounds.closeX.toDouble(), bounds.closeY.toDouble()) shouldBe true
        bounds.containsClose((bounds.closeX + bounds.closeSize).toDouble(), bounds.closeY.toDouble()) shouldBe false
        bounds.y + bounds.height shouldBeLessThanOrEqual 360
    }

    test("custom hint reserves balanced padding around text and close target") {
        val bounds = EmotionPickerHintLayout.bounds(
            screenWidth = 300,
            screenHeight = 360,
            panelX = 27,
            panelY = 67,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 160,
            lineHeight = 9,
        )

        bounds.height - bounds.closeSize shouldBe EmotionPickerHintLayout.VERTICAL_PADDING * 2
        EmotionPickerHintLayout.maximumTextWidth(300, 246) shouldBe 223
    }

    test("custom hint text uses native size and left aligned integer coordinates") {
        val bounds = EmotionPickerHintBounds(27, 297, 246, 15, 259, 300, 9)

        EmotionPickerHintTextLayout.x(bounds) shouldBe 32
        EmotionPickerHintTextLayout.y(bounds, 9) shouldBe 300
    }

    test("custom hint never grows wider than the main panel") {
        val bounds = EmotionPickerHintLayout.bounds(
            screenWidth = 300,
            screenHeight = 360,
            panelX = 27,
            panelY = 67,
            panelWidth = 246,
            panelHeight = 226,
            textWidth = 500,
            lineHeight = 9,
        )

        bounds.x shouldBe 27
        bounds.width shouldBe 246
        EmotionPickerHintLayout.maximumTextWidth(300, 246) shouldBe 223
    }

    test("custom hint animation reveals panel text and close control in soft phases") {
        EmotionPickerHintAnimation.panelOpacityAt(0.0) shouldBe 0.0
        EmotionPickerHintAnimation.panelOpacityAt(260.0) shouldBe 0.5
        EmotionPickerHintAnimation.panelOpacityAt(520.0) shouldBe 1.0
        EmotionPickerHintAnimation.textOpacityAt(99.0) shouldBe 0.0
        EmotionPickerHintAnimation.textOpacityAt(320.0) shouldBe 0.5
        EmotionPickerHintAnimation.textOpacityAt(540.0) shouldBe 1.0
        EmotionPickerHintAnimation.closeOpacityAt(219.0) shouldBe 0.0
        EmotionPickerHintAnimation.closeOpacityAt(410.0) shouldBe 0.5
        EmotionPickerHintAnimation.closeOpacityAt(600.0) shouldBe 1.0
        EmotionPickerHintAnimation.verticalOffsetAt(0.0) shouldBe -2.0
        EmotionPickerHintAnimation.verticalOffsetAt(260.0) shouldBe -1.0
        EmotionPickerHintAnimation.verticalOffsetAt(520.0) shouldBe 0.0
        EmotionPickerHintAnimation.horizontalScaleAt(0.0) shouldBe 0.985
        EmotionPickerHintAnimation.horizontalScaleAt(260.0) shouldBe 0.9925
        EmotionPickerHintAnimation.horizontalScaleAt(520.0) shouldBe 1.0
        EmotionPickerHintAnimation.textHorizontalOffsetAt(99.0) shouldBe -2.0
        EmotionPickerHintAnimation.textHorizontalOffsetAt(320.0) shouldBe -1.0
        EmotionPickerHintAnimation.textHorizontalOffsetAt(540.0) shouldBe 0.0
        EmotionPickerHintAnimation.closeScaleAt(219.0) shouldBe 0.88
        EmotionPickerHintAnimation.closeScaleAt(410.0) shouldBe 0.94
        EmotionPickerHintAnimation.closeScaleAt(600.0) shouldBe 1.0
    }

    test("custom hint closes with a smooth compact exit") {
        EmotionPickerHintAnimation.dismissOpacityAt(0.0) shouldBe 1.0
        EmotionPickerHintAnimation.dismissOpacityAt(140.0) shouldBe 0.5
        EmotionPickerHintAnimation.dismissOpacityAt(280.0) shouldBe 0.0
        EmotionPickerHintAnimation.dismissVerticalOffsetAt(0.0) shouldBe 0.0
        EmotionPickerHintAnimation.dismissVerticalOffsetAt(140.0) shouldBe -1.0
        EmotionPickerHintAnimation.dismissVerticalOffsetAt(280.0) shouldBe -2.0
        EmotionPickerHintAnimation.dismissScaleAt(0.0) shouldBe 1.0
        EmotionPickerHintAnimation.dismissScaleAt(140.0) shouldBe 0.99
        EmotionPickerHintAnimation.dismissScaleAt(280.0) shouldBe 0.98
        EmotionPickerHintAnimation.isDismissFinished(279.0) shouldBe false
        EmotionPickerHintAnimation.isDismissFinished(280.0) shouldBe true
    }

    test("custom hint close hit area follows its visual transform") {
        val bounds = EmotionPickerHintBounds(27, 297, 246, 13, 259, 299, 9)

        bounds.containsClose(
            mouseX = 261.0,
            mouseY = 302.5,
            horizontalScale = 0.98,
            verticalScale = 1.0,
            verticalOffset = -2.0,
            closeScale = 0.84,
        ) shouldBe true
        bounds.containsClose(
            mouseX = 257.0,
            mouseY = 302.5,
            horizontalScale = 0.98,
            verticalScale = 1.0,
            verticalOffset = -2.0,
            closeScale = 0.84,
        ) shouldBe false
    }
})
