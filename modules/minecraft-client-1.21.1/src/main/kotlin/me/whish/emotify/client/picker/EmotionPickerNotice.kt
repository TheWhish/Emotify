package me.whish.emotify.client.picker

import kotlin.math.min
import kotlin.math.roundToInt

object EmotionPickerNoticeAnimation {
    const val FADE_IN_MILLIS = 160.0
    const val HOLD_MILLIS = 1_900.0
    const val FADE_OUT_MILLIS = 450.0

    fun opacityAt(ageMillis: Double, sinceLastShowMillis: Double): Double {
        if (ageMillis < 0.0 || sinceLastShowMillis < 0.0) {
            return 0.0
        }
        val fadeIn = smoothStep((ageMillis / FADE_IN_MILLIS).coerceIn(0.0, 1.0))
        val fadeOutProgress = ((sinceLastShowMillis - HOLD_MILLIS) / FADE_OUT_MILLIS).coerceIn(0.0, 1.0)
        return min(fadeIn, 1.0 - smoothStep(fadeOutProgress)).coerceIn(0.0, 1.0)
    }

    fun isFinished(sinceLastShowMillis: Double): Boolean =
        sinceLastShowMillis >= HOLD_MILLIS + FADE_OUT_MILLIS

    fun renderAlpha(opacity: Double): Int {
        val alpha = (opacity.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return if (alpha < MINIMUM_FONT_ALPHA) 0 else alpha
    }

    private fun smoothStep(progress: Double): Double =
        progress * progress * (3.0 - 2.0 * progress)

    private const val MINIMUM_FONT_ALPHA = 4
}

object EmotionPickerHintAnimation {
    fun panelOpacityAt(ageMillis: Double): Double = phase(ageMillis, 0.0, PANEL_FADE_MILLIS)

    fun textOpacityAt(ageMillis: Double): Double = phase(ageMillis, TEXT_DELAY_MILLIS, TEXT_FADE_MILLIS)

    fun closeOpacityAt(ageMillis: Double): Double = phase(ageMillis, CLOSE_DELAY_MILLIS, CLOSE_FADE_MILLIS)

    fun verticalOffsetAt(ageMillis: Double): Double =
        -ENTRY_DISTANCE * (1.0 - panelOpacityAt(ageMillis))

    fun horizontalScaleAt(ageMillis: Double): Double =
        1.0 - HORIZONTAL_SCALE_DISTANCE * (1.0 - panelOpacityAt(ageMillis))

    fun textHorizontalOffsetAt(ageMillis: Double): Double =
        -TEXT_ENTRY_DISTANCE * (1.0 - textOpacityAt(ageMillis))

    fun closeScaleAt(ageMillis: Double): Double =
        1.0 - CLOSE_SCALE_DISTANCE * (1.0 - closeOpacityAt(ageMillis))

    fun dismissOpacityAt(ageMillis: Double): Double = 1.0 - phase(ageMillis, 0.0, DISMISS_MILLIS)

    fun dismissVerticalOffsetAt(ageMillis: Double): Double =
        -DISMISS_DISTANCE * phase(ageMillis, 0.0, DISMISS_MILLIS)

    fun dismissScaleAt(ageMillis: Double): Double =
        1.0 - DISMISS_SCALE_DISTANCE * phase(ageMillis, 0.0, DISMISS_MILLIS)

    fun isDismissFinished(ageMillis: Double): Boolean = ageMillis >= DISMISS_MILLIS

    private fun phase(ageMillis: Double, delayMillis: Double, durationMillis: Double): Double {
        if (ageMillis < delayMillis) {
            return 0.0
        }
        val progress = ((ageMillis - delayMillis) / durationMillis).coerceIn(0.0, 1.0)
        return progress * progress * progress * (progress * (progress * 6.0 - 15.0) + 10.0)
    }

    private const val ENTRY_DISTANCE = 2.0
    private const val HORIZONTAL_SCALE_DISTANCE = 0.015
    private const val TEXT_ENTRY_DISTANCE = 2.0
    private const val CLOSE_SCALE_DISTANCE = 0.12
    private const val PANEL_FADE_MILLIS = 520.0
    private const val TEXT_DELAY_MILLIS = 100.0
    private const val TEXT_FADE_MILLIS = 440.0
    private const val CLOSE_DELAY_MILLIS = 220.0
    private const val CLOSE_FADE_MILLIS = 380.0
    private const val DISMISS_MILLIS = 280.0
    private const val DISMISS_DISTANCE = 2.0
    private const val DISMISS_SCALE_DISTANCE = 0.02
}

data class EmotionPickerNotice(
    val message: String,
    val firstShownAtNanos: Long,
    val lastShownAtNanos: Long,
) {
    fun opacityAt(nowNanos: Long): Double =
        EmotionPickerNoticeAnimation.opacityAt(
            elapsedMillis(firstShownAtNanos, nowNanos),
            elapsedMillis(lastShownAtNanos, nowNanos),
        )

    fun isFinished(nowNanos: Long): Boolean =
        EmotionPickerNoticeAnimation.isFinished(elapsedMillis(lastShownAtNanos, nowNanos))

    companion object {
        fun show(current: EmotionPickerNotice?, message: String, nowNanos: Long): EmotionPickerNotice {
            require(message.isNotBlank()) { "Emotion picker notice must not be blank" }
            if (current != null && current.message == message && !current.isFinished(nowNanos)) {
                return current.copy(lastShownAtNanos = nowNanos)
            }
            return EmotionPickerNotice(message, nowNanos, nowNanos)
        }

        private fun elapsedMillis(startNanos: Long, nowNanos: Long): Double =
            (nowNanos - startNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

data class EmotionPickerNoticeBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class EmotionPickerHintBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val closeX: Int,
    val closeY: Int,
    val closeSize: Int,
) {
    fun containsClose(mouseX: Double, mouseY: Double): Boolean =
        EmotionPickerHitArea.contains(closeX, closeY, closeSize, closeSize, mouseX, mouseY)

    fun containsClose(
        mouseX: Double,
        mouseY: Double,
        horizontalScale: Double,
        verticalScale: Double,
        verticalOffset: Double,
        closeScale: Double,
    ): Boolean {
        require(horizontalScale > 0.0 && horizontalScale.isFinite()) { "Horizontal scale must be positive and finite" }
        require(verticalScale > 0.0 && verticalScale.isFinite()) { "Vertical scale must be positive and finite" }
        require(verticalOffset.isFinite()) { "Vertical offset must be finite" }
        require(closeScale > 0.0 && closeScale.isFinite()) { "Close scale must be positive and finite" }
        val panelCenterX = x + width / 2.0
        val panelCenterY = y + height / 2.0
        val closeCenterX = panelCenterX + (closeX + closeSize / 2.0 - panelCenterX) * horizontalScale
        val closeCenterY = panelCenterY + (closeY + closeSize / 2.0 - panelCenterY) * verticalScale + verticalOffset
        val halfWidth = closeSize / 2.0 * horizontalScale * closeScale
        val halfHeight = closeSize / 2.0 * verticalScale * closeScale
        return mouseX >= closeCenterX - halfWidth &&
            mouseX < closeCenterX + halfWidth &&
            mouseY >= closeCenterY - halfHeight &&
            mouseY < closeCenterY + halfHeight
    }
}

object EmotionPickerHintLayout {
    fun maximumTextWidth(screenWidth: Int, panelWidth: Int): Int {
        require(screenWidth > 0) { "Screen width must be positive" }
        require(panelWidth > 0) { "Panel width must be positive" }
        return (maximumHintWidth(screenWidth, panelWidth) - HORIZONTAL_PADDING * 2 - CONTENT_GAP - CLOSE_SIZE)
            .coerceAtLeast(1)
    }

    fun bounds(
        screenWidth: Int,
        screenHeight: Int,
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        textWidth: Int,
        lineHeight: Int,
    ): EmotionPickerHintBounds {
        require(textWidth >= 0) { "Text width must not be negative" }
        require(screenWidth > 0) { "Screen width must be positive" }
        require(screenHeight > 0) { "Screen height must be positive" }
        require(panelWidth > 0) { "Panel width must be positive" }
        require(panelHeight > 0) { "Panel height must be positive" }
        require(lineHeight > 0) { "Line height must be positive" }
        val maximumWidth = maximumHintWidth(screenWidth, panelWidth)
        val width = maximumWidth
        val height = maxOf(lineHeight, CLOSE_SIZE) + VERTICAL_PADDING * 2
        val maximumX = (screenWidth - SCREEN_EDGE_MARGIN - width).coerceAtLeast(0)
        val minimumX = SCREEN_EDGE_MARGIN.coerceAtMost(maximumX)
        val x = (panelX + (panelWidth - width) / 2).coerceIn(minimumX, maximumX)
        val preferredY = panelY + panelHeight + EmotionPickerVisualMetrics.GAP
        val y = when {
            preferredY + height + SCREEN_EDGE_MARGIN <= screenHeight -> preferredY
            panelY - EmotionPickerVisualMetrics.GAP - height >= SCREEN_EDGE_MARGIN ->
                panelY - EmotionPickerVisualMetrics.GAP - height
            else -> (screenHeight - SCREEN_EDGE_MARGIN - height).coerceAtLeast(0)
        }
        val closeX = x + width - HORIZONTAL_PADDING - CLOSE_SIZE
        val closeY = y + (height - CLOSE_SIZE) / 2
        return EmotionPickerHintBounds(
            x,
            y,
            width,
            height,
            closeX,
            closeY,
            CLOSE_SIZE,
        )
    }

    private fun maximumHintWidth(screenWidth: Int, panelWidth: Int): Int =
        min(panelWidth, min(MAXIMUM_WIDTH, screenWidth - SCREEN_EDGE_MARGIN * 2)).coerceAtLeast(1)

    const val HORIZONTAL_PADDING = 5
    const val VERTICAL_PADDING = 3
    const val CONTENT_GAP = EmotionPickerVisualMetrics.GAP
    const val CLOSE_SIZE = 9
    private const val MAXIMUM_WIDTH = 276
    private const val SCREEN_EDGE_MARGIN = 4
}

object EmotionPickerHintTextLayout {
    fun x(bounds: EmotionPickerHintBounds): Int = bounds.x + EmotionPickerHintLayout.HORIZONTAL_PADDING

    fun y(bounds: EmotionPickerHintBounds, lineHeight: Int): Int {
        require(lineHeight in 1..bounds.height) { "Line height is outside the hint: $lineHeight" }
        return bounds.y + (bounds.height - lineHeight) / 2
    }
}

object EmotionPickerNoticeLayout {
    fun maximumTextWidth(screenWidth: Int): Int {
        require(screenWidth > 0) { "Screen width must be positive" }
        return (maximumNoticeWidth(screenWidth) - HORIZONTAL_PADDING * 2).coerceAtLeast(1)
    }

    fun bounds(
        screenWidth: Int,
        screenHeight: Int,
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        textWidth: Int,
        lineHeight: Int,
    ): EmotionPickerNoticeBounds {
        require(screenWidth > 0) { "Screen width must be positive" }
        require(screenHeight > 0) { "Screen height must be positive" }
        require(panelWidth > 0) { "Panel width must be positive" }
        require(panelHeight > 0) { "Panel height must be positive" }
        require(textWidth >= 0) { "Text width must not be negative" }
        require(lineHeight > 0) { "Line height must be positive" }
        val maximumWidth = maximumNoticeWidth(screenWidth)
        val width = (textWidth + HORIZONTAL_PADDING * 2).coerceIn(MINIMUM_WIDTH.coerceAtMost(maximumWidth), maximumWidth)
        val height = lineHeight + VERTICAL_PADDING * 2
        val maximumX = (screenWidth - SCREEN_EDGE_MARGIN - width).coerceAtLeast(0)
        val minimumX = SCREEN_EDGE_MARGIN.coerceAtMost(maximumX)
        val x = (panelX + (panelWidth - width) / 2).coerceIn(minimumX, maximumX)
        val preferredY = panelY + panelHeight + EmotionPickerVisualMetrics.GAP
        val y = when {
            preferredY + height + SCREEN_EDGE_MARGIN <= screenHeight -> preferredY
            panelY - EmotionPickerVisualMetrics.GAP - height >= SCREEN_EDGE_MARGIN ->
                panelY - EmotionPickerVisualMetrics.GAP - height
            else -> (screenHeight - SCREEN_EDGE_MARGIN - height).coerceAtLeast(0)
        }
        return EmotionPickerNoticeBounds(
            x,
            y,
            width,
            height,
        )
    }

    private fun maximumNoticeWidth(screenWidth: Int): Int =
        min(MAXIMUM_WIDTH, screenWidth - SCREEN_EDGE_MARGIN * 2).coerceAtLeast(1)

    const val HORIZONTAL_PADDING = 6
    const val VERTICAL_PADDING = 2
    private const val MINIMUM_WIDTH = 72
    private const val MAXIMUM_WIDTH = 320
    private const val SCREEN_EDGE_MARGIN = 4
}
