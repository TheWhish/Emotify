package me.whish.emotify.client.presentation

import me.whish.emotify.domain.EmotionAnimation

data class EmotionHotbarFeedbackState(
    val alpha: Float,
    val scale: Float,
    val yOffset: Float,
    val isVisible: Boolean,
)

object EmotionHotbarFeedbackMath {
    const val FADE_IN_MILLIS = 350.0
    const val FADE_OUT_MILLIS = 450.0
    const val DEFAULT_BOTTOM_MARGIN = 56
    const val DEFAULT_ICON_SIZE = 16

    private val INVISIBLE_STATE = EmotionHotbarFeedbackState(
        alpha = 0.0f,
        scale = 0.0f,
        yOffset = 0.0f,
        isVisible = false,
    )

    fun evaluate(elapsedMillis: Double, durationMillis: Double = EmotionAnimation.DURATION_MILLIS): EmotionHotbarFeedbackState {
        if (elapsedMillis < 0.0 || elapsedMillis >= durationMillis) {
            return INVISIBLE_STATE
        }

        if (elapsedMillis < FADE_IN_MILLIS) {
            val progress = (elapsedMillis / FADE_IN_MILLIS).coerceIn(0.0, 1.0)
            val inverse = 1.0 - progress
            val easeOut = 1.0 - inverse * inverse * inverse
            val scale = (0.75 + 0.25 * easeOut).toFloat()
            val yOffset = ((1.0 - easeOut) * 4.0).toFloat()
            return EmotionHotbarFeedbackState(
                alpha = easeOut.toFloat(),
                scale = scale,
                yOffset = yOffset,
                isVisible = true,
            )
        }

        val fadeOutStart = durationMillis - FADE_OUT_MILLIS
        if (elapsedMillis >= fadeOutStart) {
            val progress = ((durationMillis - elapsedMillis) / FADE_OUT_MILLIS).coerceIn(0.0, 1.0)
            val smoothStep = progress * progress * (3.0 - 2.0 * progress)
            val scale = (0.85 + 0.15 * smoothStep).toFloat()
            val yOffset = ((1.0 - smoothStep) * -3.0).toFloat()
            return EmotionHotbarFeedbackState(
                alpha = smoothStep.toFloat(),
                scale = scale,
                yOffset = yOffset,
                isVisible = smoothStep > 0.001,
            )
        }

        return EmotionHotbarFeedbackState(
            alpha = 1.0f,
            scale = 1.0f,
            yOffset = 0.0f,
            isVisible = true,
        )
    }

    fun centerX(screenWidth: Int): Float = screenWidth * 0.5f

    fun centerY(screenHeight: Int, iconSize: Int = DEFAULT_ICON_SIZE, bottomMargin: Int = DEFAULT_BOTTOM_MARGIN): Float =
        screenHeight.toFloat() - bottomMargin.toFloat() - iconSize.toFloat() * 0.5f
}