package me.whish.emotify.domain

import kotlin.math.roundToInt

enum class AnimationMotion {
    FULL,
    REDUCED,
}

object EmotionAnimation {
    const val APPEAR_MILLIS = 150.0
    const val HOLD_END_MILLIS = 1_300.0
    const val DURATION_MILLIS = 2_200.0
    const val MAX_RISE_BLOCKS = 0.55

    fun alphaAt(elapsedMillis: Double): Double = when {
        elapsedMillis <= 0.0 -> 0.0
        elapsedMillis < APPEAR_MILLIS -> smoothStep(elapsedMillis / APPEAR_MILLIS)
        elapsedMillis <= HOLD_END_MILLIS -> 1.0
        elapsedMillis < DURATION_MILLIS ->
            1.0 - smoothStep(normalized(elapsedMillis, HOLD_END_MILLIS, DURATION_MILLIS))
        else -> 0.0
    }

    fun scaleAt(elapsedMillis: Double, motion: AnimationMotion): Double {
        if (motion == AnimationMotion.REDUCED) {
            return 1.0
        }

        return 0.75 + 0.25 * smoothStep(elapsedMillis / APPEAR_MILLIS)
    }

    fun verticalOffsetAt(elapsedMillis: Double, motion: AnimationMotion): Double {
        if (motion == AnimationMotion.REDUCED || elapsedMillis <= HOLD_END_MILLIS) {
            return 0.0
        }

        return MAX_RISE_BLOCKS * smoothStep(normalized(elapsedMillis, HOLD_END_MILLIS, DURATION_MILLIS))
    }

    fun isFinished(elapsedMillis: Double): Boolean = elapsedMillis >= DURATION_MILLIS

    fun elapsedMillis(startedAtNanos: Long, currentNanos: Long): Double {
        val elapsedNanos = currentNanos - startedAtNanos
        require(elapsedNanos >= 0L) { "Animation clock moved backwards" }
        return elapsedNanos / NANOSECONDS_PER_MILLISECOND
    }

    fun opacityByteAt(elapsedMillis: Double): Int =
        (alphaAt(elapsedMillis) * MAX_OPACITY).roundToInt().coerceIn(0, MAX_OPACITY)

    fun smoothStep(progress: Double): Double {
        if (progress.isNaN()) {
            return 0.0
        }

        val boundedProgress = progress.coerceIn(0.0, 1.0)
        return boundedProgress * boundedProgress * (3.0 - 2.0 * boundedProgress)
    }

    private fun normalized(value: Double, start: Double, end: Double): Double =
        (value - start) / (end - start)

    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000.0
    private const val MAX_OPACITY = 255
}
