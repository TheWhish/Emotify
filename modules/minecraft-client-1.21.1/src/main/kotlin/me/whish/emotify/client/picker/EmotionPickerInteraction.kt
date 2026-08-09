package me.whish.emotify.client.picker

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object EmotionPickerBrand {
    const val TITLE = "Emotify"
}

object EmotionLabelTruncation {
    fun completePrefix(source: String, prefix: String): String {
        require(source.startsWith(prefix)) { "Label prefix does not belong to its source" }
        val trimmed = prefix.trimEnd()
        val nextCharacter = source.getOrNull(prefix.length)
        if (prefix.lastOrNull()?.isWhitespace() == true || nextCharacter == null || nextCharacter.isWhitespace()) {
            return trimmed
        }
        val wordBoundary = trimmed.indexOfLast(Char::isWhitespace)
        if (wordBoundary < 0) {
            return trimmed
        }
        val fragment = trimmed.substring(wordBoundary + 1)
        val fragmentLength = fragment.codePointCount(0, fragment.length)
        return if (fragmentLength in 1 until MINIMUM_FRAGMENT_LENGTH) {
            trimmed.substring(0, wordBoundary).trimEnd()
        } else {
            trimmed
        }
    }

    private const val MINIMUM_FRAGMENT_LENGTH = 3
}

object EmotionPickerToggleGuard {
    fun shouldClose(
        matchesBinding: Boolean,
        bindingDown: Boolean,
        textInputFocused: Boolean,
    ): Boolean = matchesBinding && !bindingDown && !textInputFocused
}

object EmotionPickerKeyboardRouting {
    fun consumePress(movementAllowed: Boolean, matchesMovement: Boolean): Boolean =
        movementAllowed && matchesMovement
}

enum class EmotionPickerMouseDecision {
    CLOSE,
    CONSUME_MOVEMENT,
    DISPATCH,
}

object EmotionPickerMouseRouting {
    fun click(
        matchesPicker: Boolean,
        movementAllowed: Boolean,
        matchesMovement: Boolean,
        enteringSearch: Boolean,
    ): EmotionPickerMouseDecision = when {
        matchesPicker -> EmotionPickerMouseDecision.CLOSE
        movementAllowed && matchesMovement && !enteringSearch -> EmotionPickerMouseDecision.CONSUME_MOVEMENT
        else -> EmotionPickerMouseDecision.DISPATCH
    }

    fun consumeRelease(movementAllowed: Boolean, matchesMovement: Boolean): Boolean =
        movementAllowed && matchesMovement
}

object EmotionPickerDragGesture {
    const val THRESHOLD = 4.0

    fun shouldStart(originX: Double, originY: Double, mouseX: Double, mouseY: Double): Boolean {
        val deltaX = mouseX - originX
        val deltaY = mouseY - originY
        return deltaX * deltaX + deltaY * deltaY >= THRESHOLD * THRESHOLD
    }
}

object EmotionPickerDragPreview {
    class Motion(
        var x: Double,
        var y: Double,
        var velocityX: Double = 0.0,
        var velocityY: Double = 0.0,
    ) {
        fun distanceTo(targetX: Double, targetY: Double): Double {
            val deltaX = targetX - x
            val deltaY = targetY - y
            return sqrt(deltaX * deltaX + deltaY * deltaY)
        }
    }

    const val MAXIMUM_LAG = 16.0

    fun liftScale(elapsedNanos: Long): Double {
        if (elapsedNanos >= LIFT_DURATION_NANOS) {
            return 1.0
        }
        val progress = elapsedNanos.coerceAtLeast(0L).toDouble() / LIFT_DURATION_NANOS
        val remaining = 1.0 - progress
        val easedProgress = 1.0 - remaining * remaining * remaining
        val lift = sin(PI * progress) * remaining * LIFT_OVERSHOOT
        return INITIAL_LIFT_SCALE + (1.0 - INITIAL_LIFT_SCALE) * easedProgress + lift
    }

    fun tiltDegrees(motion: Motion): Double =
        (motion.velocityX * TILT_PER_VELOCITY).coerceIn(-MAXIMUM_TILT_DEGREES, MAXIMUM_TILT_DEGREES)

    fun advance(
        motion: Motion,
        targetX: Double,
        targetY: Double,
        elapsedSeconds: Double,
    ) {
        require(
            motion.x.isFinite() &&
                motion.y.isFinite() &&
                motion.velocityX.isFinite() &&
                motion.velocityY.isFinite() &&
                targetX.isFinite() &&
                targetY.isFinite(),
        ) {
            "Drag preview motion must contain only finite values"
        }
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Drag preview elapsed time must be finite and non-negative: $elapsedSeconds"
        }
        if (elapsedSeconds == 0.0) {
            return
        }
        val elapsed = elapsedSeconds.coerceAtMost(MAXIMUM_FRAME_SECONDS)
        val decay = exp(-ANGULAR_FREQUENCY * elapsed)
        val displacementX = motion.x - targetX
        val impulseX = (motion.velocityX + ANGULAR_FREQUENCY * displacementX) * elapsed
        val displacementY = motion.y - targetY
        val impulseY = (motion.velocityY + ANGULAR_FREQUENCY * displacementY) * elapsed
        motion.x = targetX + (displacementX + impulseX) * decay
        motion.y = targetY + (displacementY + impulseY) * decay
        motion.velocityX = ((motion.velocityX - ANGULAR_FREQUENCY * impulseX) * decay)
            .coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
        motion.velocityY = ((motion.velocityY - ANGULAR_FREQUENCY * impulseY) * decay)
            .coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
        clampLag(motion, targetX, targetY)
        if (
            motion.distanceTo(targetX, targetY) <= SNAP_DISTANCE &&
            abs(motion.velocityX) <= SNAP_VELOCITY &&
            abs(motion.velocityY) <= SNAP_VELOCITY
        ) {
            motion.x = targetX
            motion.y = targetY
            motion.velocityX = 0.0
            motion.velocityY = 0.0
        }
    }

    private fun clampLag(motion: Motion, targetX: Double, targetY: Double) {
        val deltaX = targetX - motion.x
        val deltaY = targetY - motion.y
        val distanceSquared = deltaX * deltaX + deltaY * deltaY
        if (distanceSquared <= MAXIMUM_LAG * MAXIMUM_LAG) {
            return
        }
        val scale = MAXIMUM_LAG / sqrt(distanceSquared)
        motion.x = targetX - deltaX * scale
        motion.y = targetY - deltaY * scale
        val radialVelocityScale = (motion.velocityX * deltaX + motion.velocityY * deltaY) / distanceSquared
        if (radialVelocityScale < 0.0) {
            motion.velocityX -= deltaX * radialVelocityScale
            motion.velocityY -= deltaY * radialVelocityScale
        }
    }

    private const val LIFT_DURATION_NANOS = 160_000_000L
    private const val INITIAL_LIFT_SCALE = 0.86
    private const val LIFT_OVERSHOOT = 0.08
    private const val TILT_PER_VELOCITY = 0.01
    private const val MAXIMUM_TILT_DEGREES = 3.5
    private const val ANGULAR_FREQUENCY = 16.0
    private const val MAXIMUM_FRAME_SECONDS = 0.05
    private const val MAXIMUM_VELOCITY = 360.0
    private const val SNAP_DISTANCE = 0.01
    private const val SNAP_VELOCITY = 0.05
}

enum class EmotionPickerQuickSlotMouseDecision {
    DISPATCH,
    CONSUME_EMPTY,
    ACTIVATE,
    CLEAR,
}

object EmotionPickerQuickSlotMouseRouting {
    fun click(
        assigned: Boolean,
        hovered: Boolean,
        button: Int,
    ): EmotionPickerQuickSlotMouseDecision = when {
        !hovered -> EmotionPickerQuickSlotMouseDecision.DISPATCH
        !assigned && button in 0..1 -> EmotionPickerQuickSlotMouseDecision.CONSUME_EMPTY
        assigned && button == 0 -> EmotionPickerQuickSlotMouseDecision.ACTIVATE
        assigned && button == 1 -> EmotionPickerQuickSlotMouseDecision.CLEAR
        else -> EmotionPickerQuickSlotMouseDecision.DISPATCH
    }
}

object EmotionPickerQuickSlotAnimation {
    const val DURATION_NANOS = 320_000_000L

    fun isLanding(elapsedNanos: Long): Boolean = elapsedNanos in 0 until DURATION_NANOS

    fun landingOffset(elapsedNanos: Long): Double {
        val progress = landingProgress(elapsedNanos)
        if (progress >= 1.0) {
            return 0.0
        }
        val remaining = 1.0 - progress
        return -LANDING_DISTANCE * remaining * remaining * cos(TWO_PI * progress)
    }

    fun landingScale(elapsedNanos: Long): Double {
        val progress = landingProgress(elapsedNanos)
        if (progress >= 1.0) {
            return 1.0
        }
        val remaining = 1.0 - progress
        return 1.0 - LANDING_SCALE_DEPTH * remaining * remaining * cos(TWO_PI * progress)
    }

    fun landingEmphasis(elapsedNanos: Long): Double {
        val remaining = 1.0 - landingProgress(elapsedNanos)
        return remaining * remaining
    }

    fun nextTargetEmphasis(current: Double, targeted: Boolean, elapsedSeconds: Double): Double {
        require(current.isFinite() && current in 0.0..1.0) {
            "Quick-slot target emphasis is outside the supported range: $current"
        }
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Quick-slot target elapsed time must be finite and non-negative: $elapsedSeconds"
        }
        val target = if (targeted) 1.0 else 0.0
        val elapsed = elapsedSeconds.coerceAtMost(MAXIMUM_TARGET_FRAME_SECONDS)
        val updated = current + (target - current) * (1.0 - exp(-TARGET_RESPONSE * elapsed))
        return if (abs(target - updated) <= TARGET_SNAP_DISTANCE) target else updated.coerceIn(0.0, 1.0)
    }

    private fun landingProgress(elapsedNanos: Long): Double =
        (elapsedNanos.coerceAtLeast(0L).toDouble() / DURATION_NANOS).coerceAtMost(1.0)

    private const val LANDING_DISTANCE = 3.0
    private const val LANDING_SCALE_DEPTH = 0.18
    private const val TWO_PI = PI * 2.0
    private const val TARGET_RESPONSE = 18.0
    private const val MAXIMUM_TARGET_FRAME_SECONDS = 0.05
    private const val TARGET_SNAP_DISTANCE = 0.001
}
