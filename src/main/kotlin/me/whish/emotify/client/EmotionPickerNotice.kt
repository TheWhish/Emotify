package me.whish.emotify.client

import kotlin.math.min
import kotlin.math.roundToInt

internal object EmotionPickerNoticeAnimation {
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

internal data class EmotionPickerNotice(
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

internal data class EmotionPickerNoticeBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal object EmotionPickerNoticeLayout {
    fun maximumTextWidth(panelWidth: Int): Int =
        (panelWidth - OUTER_INSET * 2 - HORIZONTAL_PADDING * 2).coerceAtLeast(1)

    fun bounds(
        screenHeight: Int,
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        textWidth: Int,
        lineHeight: Int,
    ): EmotionPickerNoticeBounds {
        require(screenHeight > 0) { "Screen height must be positive" }
        require(panelWidth > 0) { "Panel width must be positive" }
        require(panelHeight > 0) { "Panel height must be positive" }
        require(textWidth >= 0) { "Text width must not be negative" }
        require(lineHeight > 0) { "Line height must be positive" }
        val maximumWidth = (panelWidth - OUTER_INSET * 2).coerceAtLeast(1)
        val width = (textWidth + HORIZONTAL_PADDING * 2).coerceIn(MINIMUM_WIDTH.coerceAtMost(maximumWidth), maximumWidth)
        val height = lineHeight + VERTICAL_PADDING * 2
        val preferredY = panelY + panelHeight + EmotionPickerVisualMetrics.GAP
        val y = when {
            preferredY + height + SCREEN_EDGE_MARGIN <= screenHeight -> preferredY
            panelY - EmotionPickerVisualMetrics.GAP - height >= SCREEN_EDGE_MARGIN ->
                panelY - EmotionPickerVisualMetrics.GAP - height
            else -> (screenHeight - SCREEN_EDGE_MARGIN - height).coerceAtLeast(0)
        }
        return EmotionPickerNoticeBounds(
            panelX + (panelWidth - width) / 2,
            y,
            width,
            height,
        )
    }

    const val HORIZONTAL_PADDING = 6
    const val VERTICAL_PADDING = 2
    private const val MINIMUM_WIDTH = 72
    private const val SCREEN_EDGE_MARGIN = 4
    private const val OUTER_INSET = EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
}
