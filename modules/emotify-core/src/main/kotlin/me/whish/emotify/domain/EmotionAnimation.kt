package me.whish.emotify.domain

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class AnimationMotion {
    FULL,
    REDUCED,
}

enum class EmotionAnimationVariant {
    ELASTIC_POP,
    RIBBON_WEAVE,
    LANTERN_RELEASE,
    ECHO_BLOOM,
}

class EmotionAnimationFrameBuffer {
    private val horizontalOffsets = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT)
    private val verticalOffsets = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT)
    private val diameters = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT) {
        EmotionAnimation.MIN_DIAMETER_BLOCKS
    }
    private val horizontalScales = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT) { 1.0 }
    private val verticalScales = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT) { 1.0 }
    private val alphas = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT)

    var spriteCount: Int = 0
        private set

    fun horizontalOffsetAt(spriteIndex: Int): Double = horizontalOffsets[spriteIndex]

    fun verticalOffsetAt(spriteIndex: Int): Double = verticalOffsets[spriteIndex]

    fun diameterAt(spriteIndex: Int): Double = diameters[spriteIndex]

    fun horizontalScaleAt(spriteIndex: Int): Double = horizontalScales[spriteIndex]

    fun verticalScaleAt(spriteIndex: Int): Double = verticalScales[spriteIndex]

    fun alphaAt(spriteIndex: Int): Double = alphas[spriteIndex]

    fun opacityByteAt(spriteIndex: Int): Int =
        (alphas[spriteIndex] * MAX_OPACITY).roundToInt().coerceIn(0, MAX_OPACITY)

    internal fun prepare(activeSpriteCount: Int) {
        require(activeSpriteCount in 1..EmotionAnimation.MAX_SPRITE_COUNT) {
            "Active sprite count is outside the animation buffer: $activeSpriteCount"
        }
        spriteCount = activeSpriteCount
        var spriteIndex = activeSpriteCount
        while (spriteIndex < EmotionAnimation.MAX_SPRITE_COUNT) {
            set(spriteIndex, 0.0, 0.0, EmotionAnimation.MIN_DIAMETER_BLOCKS, 0.0)
            spriteIndex++
        }
    }

    internal fun set(
        spriteIndex: Int,
        horizontalOffset: Double,
        verticalOffset: Double,
        diameter: Double,
        alpha: Double,
        horizontalScale: Double = 1.0,
        verticalScale: Double = 1.0,
    ) {
        horizontalOffsets[spriteIndex] = horizontalOffset
        verticalOffsets[spriteIndex] = verticalOffset
        diameters[spriteIndex] = diameter
        horizontalScales[spriteIndex] = horizontalScale
        verticalScales[spriteIndex] = verticalScale
        alphas[spriteIndex] = alpha
    }

    private companion object {
        const val MAX_OPACITY = 255
    }
}

object EmotionAnimation {
    const val MAX_SPRITE_COUNT = 3
    const val DURATION_MILLIS = 3_000.0
    const val MAX_HORIZONTAL_OFFSET_BLOCKS = 0.35
    const val MIN_VERTICAL_OFFSET_BLOCKS = -0.18
    const val MAX_VERTICAL_OFFSET_BLOCKS = 1.10
    const val MIN_DIAMETER_BLOCKS = 0.08
    const val MAX_DIAMETER_BLOCKS = 0.30
    const val MIN_RENDER_SCALE = 0.82
    const val MAX_RENDER_SCALE = 1.18
    const val MAX_BOTTOM_EXTENT_BLOCKS = 0.27

    fun seedFor(
        sourceMostSignificantBits: Long,
        sourceLeastSignificantBits: Long,
        sequence: Long,
        emotionId: EmotionId,
    ): Long {
        require(sequence > 0L) { "Animation sequence must be positive: $sequence" }
        val identity = sourceMostSignificantBits xor java.lang.Long.rotateLeft(sourceLeastSignificantBits, 29)
        val event = sequence * SEQUENCE_MIXER
        return mix64(identity xor event xor stableEmotionHash(emotionId))
    }

    fun variantFor(seed: Long): EmotionAnimationVariant = when ((seed ushr 1) % VARIANT_COUNT) {
        0L -> EmotionAnimationVariant.ELASTIC_POP
        1L -> EmotionAnimationVariant.RIBBON_WEAVE
        2L -> EmotionAnimationVariant.LANTERN_RELEASE
        else -> EmotionAnimationVariant.ECHO_BLOOM
    }

    fun isMirrored(seed: Long): Boolean = seed and MIRROR_BIT != 0L

    fun sampleInto(
        elapsedMillis: Double,
        motion: AnimationMotion,
        seed: Long,
        target: EmotionAnimationFrameBuffer,
    ) {
        val elapsed = boundedElapsed(elapsedMillis)
        val variant = variantFor(seed)
        val direction = if (isMirrored(seed)) -1.0 else 1.0
        if (motion == AnimationMotion.REDUCED) {
            sampleReduced(elapsed, variant, direction, target)
            return
        }
        when (variant) {
            EmotionAnimationVariant.ELASTIC_POP -> sampleElasticPop(elapsed, direction, target)
            EmotionAnimationVariant.RIBBON_WEAVE -> sampleRibbonWeave(elapsed, direction, target)
            EmotionAnimationVariant.LANTERN_RELEASE -> sampleLanternRelease(elapsed, direction, target)
            EmotionAnimationVariant.ECHO_BLOOM -> sampleEchoBloom(elapsed, direction, target)
        }
    }

    fun isFinished(elapsedMillis: Double): Boolean = elapsedMillis >= DURATION_MILLIS

    fun elapsedMillis(startedAtNanos: Long, currentNanos: Long): Double {
        val elapsedNanos = currentNanos - startedAtNanos
        require(elapsedNanos >= 0L) { "Animation clock moved backwards" }
        return elapsedNanos / NANOSECONDS_PER_MILLISECOND
    }

    private fun sampleElasticPop(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        target.prepare(1)
        val seconds = positiveSeconds(elapsed)
        val spring = springResponse(seconds, ELASTIC_DAMPING_RATIO, ELASTIC_SPRING_FREQUENCY)
        val springVelocity = elasticSpringVelocity(seconds)
        val living = livingWeight(elapsed, 620.0, ELASTIC_EXIT_START)
        val exitProgress = boundedProgress(
            (elapsed - ELASTIC_EXIT_START) / (DURATION_MILLIS - ELASTIC_EXIT_START),
        )
        val exit = acceleratedExit(exitProgress)
        val verticalVelocity = ELASTIC_ENTRY_DISTANCE * springVelocity +
            3.0 * ELASTIC_EXIT_DISTANCE * exitProgress * exitProgress /
            ((DURATION_MILLIS - ELASTIC_EXIT_START) / MILLISECONDS_PER_SECOND)
        val deformation = (verticalVelocity / ELASTIC_DEFORMATION_VELOCITY).coerceIn(-1.0, 1.0)
        val anticipation = ELASTIC_ANTICIPATION * smoothPulse(elapsed, 0.0, 220.0)
        val horizontalScale = 1.0 - ELASTIC_HORIZONTAL_STRETCH * deformation + anticipation
        val verticalScale = 1.0 + ELASTIC_VERTICAL_STRETCH * deformation - anticipation * 0.8
        val horizontal =
            ELASTIC_ENTRY_CURVE * exp(-ELASTIC_CURVE_DECAY * seconds) * sin(5.8 * seconds) +
                ELASTIC_LIVING_SWAY * LIVING_OSCILLATION_SCALE * living * sin(2.1 * seconds + 0.4)
        val vertical = ELASTIC_START_Y + ELASTIC_ENTRY_DISTANCE * spring +
            ELASTIC_LIVING_RISE * smootherStep((elapsed - 720.0) / 650.0) +
            ELASTIC_LIVING_BOB * LIVING_OSCILLATION_SCALE * living * sin(2.8 * seconds + 0.3) +
            ELASTIC_EXIT_DISTANCE * exit
        target.set(
            spriteIndex = 0,
            horizontalOffset = direction * horizontal,
            verticalOffset = vertical,
            diameter = springDiameter(0.285, elapsed, 0.0, 1_580.0 + HOLD_EXTENSION_MILLIS),
            alpha = fadeAlpha(elapsed, 0.0, 320.0, 1_580.0 + HOLD_EXTENSION_MILLIS),
            horizontalScale = horizontalScale,
            verticalScale = verticalScale,
        )
    }

    private fun sampleRibbonWeave(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        target.prepare(3)
        sampleRibbonSprite(
            target = target,
            spriteIndex = 0,
            elapsed = elapsed,
            direction = direction,
            motionStart = 0.0,
            restingX = -0.16,
            restingY = 0.10,
            weaveAmplitude = 0.035,
            phase = 0.0,
            diameter = 0.27,
            enterStart = 0.0,
            enterEnd = 300.0,
            exitStart = 1_540.0 + HOLD_EXTENSION_MILLIS,
        )
        sampleRibbonSprite(
            target = target,
            spriteIndex = 1,
            elapsed = elapsed,
            direction = direction,
            motionStart = 180.0,
            restingX = 0.13,
            restingY = 0.39,
            weaveAmplitude = 0.032,
            phase = 2.1,
            diameter = 0.21,
            enterStart = 180.0,
            enterEnd = 480.0,
            exitStart = 1_580.0 + HOLD_EXTENSION_MILLIS,
        )
        sampleRibbonSprite(
            target = target,
            spriteIndex = 2,
            elapsed = elapsed,
            direction = direction,
            motionStart = 360.0,
            restingX = -0.055,
            restingY = 0.64,
            weaveAmplitude = 0.028,
            phase = 4.2,
            diameter = 0.16,
            enterStart = 360.0,
            enterEnd = 660.0,
            exitStart = 1_620.0 + HOLD_EXTENSION_MILLIS,
        )
    }

    private fun sampleRibbonSprite(
        target: EmotionAnimationFrameBuffer,
        spriteIndex: Int,
        elapsed: Double,
        direction: Double,
        motionStart: Double,
        restingX: Double,
        restingY: Double,
        weaveAmplitude: Double,
        phase: Double,
        diameter: Double,
        enterStart: Double,
        enterEnd: Double,
        exitStart: Double,
    ) {
        val localSeconds = positiveSeconds(elapsed - motionStart)
        val spring = springResponse(localSeconds, RIBBON_DAMPING_RATIO, RIBBON_SPRING_FREQUENCY)
        val living = livingWeight(elapsed, motionStart + 620.0, exitStart)
        val horizontal = restingX +
            weaveAmplitude * LIVING_OSCILLATION_SCALE * sin(RIBBON_FREQUENCY * localSeconds + phase)
        val vertical = restingY - RIBBON_ENTRY_DISTANCE + RIBBON_ENTRY_DISTANCE * spring +
            RIBBON_BOB_AMPLITUDE * LIVING_OSCILLATION_SCALE * living *
            sin(RIBBON_BOB_FREQUENCY * localSeconds + phase) +
            RIBBON_EXIT_DISTANCE * acceleratedExit((elapsed - exitStart) / (DURATION_MILLIS - exitStart))
        target.set(
            spriteIndex = spriteIndex,
            horizontalOffset = direction * horizontal,
            verticalOffset = vertical,
            diameter = springDiameter(diameter, elapsed, enterStart, exitStart),
            alpha = fadeAlpha(elapsed, enterStart, enterEnd, exitStart),
        )
    }

    private fun sampleLanternRelease(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        target.prepare(2)
        sampleLanternAnchor(elapsed, direction, target)
        sampleLanternLight(elapsed, direction, target)
    }

    private fun sampleLanternAnchor(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        val seconds = positiveSeconds(elapsed)
        val spring = springResponse(seconds, 0.72, 8.5)
        val living = livingWeight(elapsed, 500.0, LANTERN_ANCHOR_LIFT_START)
        val exit = smoothFlightProgress(
            (elapsed - LANTERN_ANCHOR_LIFT_START) / (DURATION_MILLIS - LANTERN_ANCHOR_LIFT_START),
        )
        val horizontal = -0.04 +
            0.025 * exp(-2.6 * seconds) * sin(5.6 * seconds) +
            0.014 * LIVING_OSCILLATION_SCALE * living * sin(2.2 * seconds + 0.2)
        val vertical = -0.10 + 0.34 * spring +
            0.012 * LIVING_OSCILLATION_SCALE * living * sin(2.7 * seconds + 0.5) +
            LANTERN_ANCHOR_EXIT_DISTANCE * exit
        val pulse = 1.0 + 0.035 * smoothPulse(elapsed, 720.0, 1_120.0)
        target.set(
            spriteIndex = 0,
            horizontalOffset = direction * horizontal,
            verticalOffset = vertical,
            diameter = springDiameter(0.275, elapsed, 0.0, LANTERN_ANCHOR_FADE_START) * pulse,
            alpha = fadeAlpha(elapsed, 0.0, 320.0, LANTERN_ANCHOR_FADE_START),
        )
    }

    private fun sampleLanternLight(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        val localSeconds = positiveSeconds(elapsed - LANTERN_LIGHT_MOTION_START)
        val spring = springResponse(localSeconds, 0.66, 8.8)
        val living = livingWeight(elapsed, 1_120.0, LANTERN_LIGHT_EXIT_START)
        val horizontal = 0.09 +
            0.025 * exp(-2.5 * localSeconds) * sin(6.0 * localSeconds) +
            0.010 * LIVING_OSCILLATION_SCALE * living * sin(2.5 * localSeconds + 1.4)
        val vertical = 0.393 + 0.19 * spring +
            0.015 * smootherStep((elapsed - 760.0) / 500.0) +
            0.010 * LIVING_OSCILLATION_SCALE * living * sin(3.0 * localSeconds + 0.8) +
            0.32 * smoothFlightProgress(
                (elapsed - LANTERN_LIGHT_EXIT_START) /
                    (DURATION_MILLIS - LANTERN_LIGHT_EXIT_START),
            )
        target.set(
            spriteIndex = 1,
            horizontalOffset = direction * horizontal,
            verticalOffset = vertical,
            diameter = springDiameter(0.20, elapsed, 210.0, LANTERN_LIGHT_EXIT_START),
            alpha = fadeAlpha(elapsed, 210.0, 490.0, LANTERN_LIGHT_EXIT_START),
        )
    }

    private fun sampleEchoBloom(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        target.prepare(3)
        sampleEchoCore(elapsed, direction, target)
        sampleEchoPetal(
            elapsed,
            direction,
            target,
            1,
            -1.0,
            ECHO_LEFT_START,
            ECHO_LEFT_LIFT_START,
            ECHO_LEFT_FADE_START,
        )
        sampleEchoPetal(
            elapsed,
            direction,
            target,
            2,
            1.0,
            ECHO_RIGHT_START,
            ECHO_RIGHT_LIFT_START,
            ECHO_RIGHT_FADE_START,
        )
    }

    private fun sampleEchoCore(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        val seconds = positiveSeconds(elapsed)
        val spring = springResponse(seconds, ECHO_CORE_DAMPING_RATIO, ECHO_CORE_SPRING_FREQUENCY)
        val living = livingWeight(elapsed, ECHO_CORE_LIVING_START, ECHO_CORE_LIFT_START)
        val horizontalLiving = livingWeight(
            elapsed,
            ECHO_CORE_LIVING_START,
            ECHO_CORE_LIFT_START - LIVING_BLEND_MILLIS,
        )
        val phase = ECHO_LIVING_FREQUENCY * seconds + ECHO_LIVING_PHASE
        val exit = smoothFlightProgress(
            (elapsed - ECHO_CORE_LIFT_START) / (DURATION_MILLIS - ECHO_CORE_LIFT_START),
        )
        val pulse = 1.0 + ECHO_CORE_PULSE_SCALE * smoothPulse(
            elapsed,
            ECHO_CORE_PULSE_START,
            ECHO_CORE_PULSE_END,
        )
        val breathing = 1.0 + ECHO_CORE_BREATHING_SCALE * living * sin(phase)
        val horizontal = ECHO_CORE_SWAY * LIVING_OSCILLATION_SCALE * horizontalLiving * sin(phase)
        val vertical = ELASTIC_START_Y + ELASTIC_ENTRY_DISTANCE * spring +
            ECHO_CORE_RISE * smootherStep((elapsed - ECHO_CORE_RISE_START) / ECHO_CORE_RISE_DURATION) +
            ECHO_CORE_BOB * LIVING_OSCILLATION_SCALE * living * cos(phase) +
            ECHO_CORE_EXIT_DISTANCE * exit
        target.set(
            spriteIndex = 0,
            horizontalOffset = direction * horizontal,
            verticalOffset = vertical,
            diameter = echoDiameter(
                ECHO_CORE_DIAMETER,
                elapsed,
                0.0,
                ECHO_CORE_FADE_IN_END,
                ECHO_CORE_FADE_START,
                ECHO_CORE_ENTRY_SCALE,
            ) * pulse * breathing,
            alpha = fadeAlpha(elapsed, 0.0, ECHO_CORE_FADE_IN_END, ECHO_CORE_FADE_START),
        )
    }

    private fun sampleEchoPetal(
        elapsed: Double,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
        spriteIndex: Int,
        side: Double,
        enterStart: Double,
        liftStart: Double,
        fadeStart: Double,
    ) {
        val motionStart = enterStart - ECHO_PETAL_MOTION_LEAD
        val localSeconds = positiveSeconds(elapsed - motionStart)
        val seconds = positiveSeconds(elapsed)
        val spring = springResponse(localSeconds, ECHO_PETAL_DAMPING_RATIO, ECHO_PETAL_SPRING_FREQUENCY)
        val living = livingWeight(elapsed, enterStart + ECHO_PETAL_LIVING_DELAY, liftStart)
        val horizontalLiving = livingWeight(
            elapsed,
            enterStart + ECHO_PETAL_LIVING_DELAY,
            liftStart - LIVING_BLEND_MILLIS,
        )
        val phase = ECHO_LIVING_FREQUENCY * seconds + ECHO_LIVING_PHASE
        val exit = smoothFlightProgress(
            (elapsed - liftStart) / (DURATION_MILLIS - liftStart),
        )
        val breathing = 1.0 - ECHO_PETAL_BREATHING_SCALE * living * sin(phase)
        val radius = ECHO_PETAL_X * spring +
            ECHO_PETAL_SWAY * LIVING_OSCILLATION_SCALE * horizontalLiving * sin(phase)
        val vertical = ECHO_PETAL_START_Y + ECHO_PETAL_ENTRY_Y * spring +
            ECHO_PETAL_BOB * LIVING_OSCILLATION_SCALE * living * cos(phase) +
            ECHO_PETAL_EXIT_Y * exit
        target.set(
            spriteIndex = spriteIndex,
            horizontalOffset = direction * side * radius,
            verticalOffset = vertical,
            diameter = echoDiameter(
                ECHO_PETAL_DIAMETER,
                elapsed,
                enterStart,
                enterStart + ECHO_PETAL_FADE_DURATION,
                fadeStart,
                ECHO_PETAL_ENTRY_SCALE,
            ) * breathing,
            alpha = fadeAlpha(
                elapsed,
                enterStart,
                enterStart + ECHO_PETAL_FADE_DURATION,
                fadeStart,
            ),
        )
    }

    private fun sampleReduced(
        elapsed: Double,
        variant: EmotionAnimationVariant,
        direction: Double,
        target: EmotionAnimationFrameBuffer,
    ) {
        val alpha = fadeAlpha(elapsed, 0.0, 360.0, 1_700.0 + HOLD_EXTENSION_MILLIS)
        when (variant) {
            EmotionAnimationVariant.ELASTIC_POP -> {
                target.prepare(1)
                target.set(0, 0.0, 0.24, 0.285, alpha)
            }
            EmotionAnimationVariant.RIBBON_WEAVE -> {
                target.prepare(3)
                target.set(0, direction * -0.16, 0.10, 0.27, alpha)
                target.set(1, direction * 0.13, 0.39, 0.21, alpha)
                target.set(2, direction * -0.055, 0.64, 0.16, alpha)
            }
            EmotionAnimationVariant.LANTERN_RELEASE -> {
                target.prepare(2)
                target.set(0, direction * -0.04, 0.20, 0.275, alpha)
                target.set(1, direction * 0.09, 0.55, 0.20, alpha)
            }
            EmotionAnimationVariant.ECHO_BLOOM -> {
                target.prepare(3)
                target.set(0, 0.0, 0.255, ECHO_CORE_DIAMETER, alpha)
                target.set(1, direction * -ECHO_PETAL_X, 0.535, ECHO_PETAL_DIAMETER, alpha)
                target.set(2, direction * ECHO_PETAL_X, 0.535, ECHO_PETAL_DIAMETER, alpha)
            }
        }
    }

    private fun springDiameter(
        baseDiameter: Double,
        elapsed: Double,
        enterStart: Double,
        exitStart: Double,
    ): Double {
        val entry = springResponse(positiveSeconds(elapsed - enterStart), 0.74, 11.0).coerceIn(0.0, 1.06)
        val exit = smootherStep((elapsed - exitStart) / (DURATION_MILLIS - exitStart))
        return baseDiameter * lerp(0.84, 1.0, entry) * lerp(1.0, 0.92, exit)
    }

    private fun echoDiameter(
        baseDiameter: Double,
        elapsed: Double,
        enterStart: Double,
        enterEnd: Double,
        exitStart: Double,
        entryScale: Double,
    ): Double {
        val entry = smootherStep((elapsed - enterStart) / (enterEnd - enterStart))
        val exit = smootherStep((elapsed - exitStart) / (DURATION_MILLIS - exitStart))
        return baseDiameter * lerp(entryScale, 1.0, entry) * lerp(1.0, 0.92, exit)
    }

    private fun fadeAlpha(
        elapsed: Double,
        enterStart: Double,
        enterEnd: Double,
        exitStart: Double,
    ): Double {
        val entry = smootherStep((elapsed - enterStart) / (enterEnd - enterStart))
        val exit = smootherStep((elapsed - exitStart) / (DURATION_MILLIS - exitStart))
        return entry * (1.0 - exit)
    }

    private fun springResponse(
        elapsedSeconds: Double,
        dampingRatio: Double,
        angularFrequency: Double,
    ): Double {
        if (elapsedSeconds <= 0.0) {
            return 0.0
        }
        val oscillation = sqrt(1.0 - dampingRatio * dampingRatio)
        val dampedFrequency = angularFrequency * oscillation
        val decay = exp(-dampingRatio * angularFrequency * elapsedSeconds)
        return 1.0 - decay * (
            cos(dampedFrequency * elapsedSeconds) +
                dampingRatio / oscillation * sin(dampedFrequency * elapsedSeconds)
            )
    }

    private fun elasticSpringVelocity(elapsedSeconds: Double): Double {
        if (elapsedSeconds <= 0.0) {
            return 0.0
        }
        val dampedFrequency = ELASTIC_SPRING_FREQUENCY * sqrt(1.0 - ELASTIC_DAMPING_RATIO * ELASTIC_DAMPING_RATIO)
        return exp(-ELASTIC_DAMPING_RATIO * ELASTIC_SPRING_FREQUENCY * elapsedSeconds) *
            ELASTIC_SPRING_FREQUENCY * ELASTIC_SPRING_FREQUENCY / dampedFrequency *
            sin(dampedFrequency * elapsedSeconds)
    }

    private fun livingWeight(
        elapsed: Double,
        enterEnd: Double,
        exitStart: Double,
    ): Double =
        smootherStep((elapsed - enterEnd) / LIVING_BLEND_MILLIS) *
            (1.0 - smootherStep((elapsed - exitStart) / LIVING_BLEND_MILLIS))

    private fun smoothPulse(
        elapsed: Double,
        start: Double,
        end: Double,
    ): Double {
        val progress = (elapsed - start) / (end - start)
        return when {
            progress <= 0.0 || progress >= 1.0 -> 0.0
            progress < 0.5 -> smootherStep(progress * 2.0)
            else -> 1.0 - smootherStep(progress * 2.0 - 1.0)
        }
    }

    private fun acceleratedExit(progress: Double): Double {
        val bounded = boundedProgress(progress)
        return bounded * bounded * bounded
    }

    private fun smoothFlightProgress(progress: Double): Double {
        val bounded = boundedProgress(progress)
        return bounded * bounded * (2.0 - bounded)
    }

    private fun smootherStep(progress: Double): Double {
        val bounded = boundedProgress(progress)
        return bounded * bounded * bounded * (bounded * (bounded * 6.0 - 15.0) + 10.0)
    }

    private fun boundedProgress(progress: Double): Double = progress.coerceIn(0.0, 1.0)

    private fun positiveSeconds(elapsedMillis: Double): Double =
        elapsedMillis.coerceAtLeast(0.0) / MILLISECONDS_PER_SECOND

    private fun boundedElapsed(elapsedMillis: Double): Double = when {
        elapsedMillis.isNaN() || elapsedMillis <= 0.0 -> 0.0
        elapsedMillis >= DURATION_MILLIS -> DURATION_MILLIS
        else -> elapsedMillis
    }

    private fun lerp(start: Double, end: Double, progress: Double): Double =
        start + (end - start) * progress

    private fun stableEmotionHash(emotionId: EmotionId): Long {
        var hash = FNV_OFFSET_BASIS
        var index = 0
        while (index < emotionId.value.length) {
            hash = (hash xor emotionId.value[index].code.toLong()) * FNV_PRIME
            index++
        }
        return hash
    }

    private fun mix64(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * SPLIT_MIX_FIRST
        mixed = (mixed xor (mixed ushr 27)) * SPLIT_MIX_SECOND
        return mixed xor (mixed ushr 31)
    }

    private const val VARIANT_COUNT = 4L
    private const val MIRROR_BIT = 1L
    private const val ELASTIC_DAMPING_RATIO = 0.58
    private const val ELASTIC_SPRING_FREQUENCY = 8.3
    private const val ELASTIC_START_Y = -0.14
    private const val ELASTIC_ENTRY_DISTANCE = 0.38
    private const val ELASTIC_ENTRY_CURVE = 0.042
    private const val ELASTIC_CURVE_DECAY = 2.4
    private const val ELASTIC_LIVING_SWAY = 0.018
    private const val ELASTIC_LIVING_RISE = 0.020
    private const val ELASTIC_LIVING_BOB = 0.012
    private const val HOLD_EXTENSION_MILLIS = 800.0
    private const val LIVING_OSCILLATION_SCALE = 0.92
    private const val ELASTIC_EXIT_START = 1_550.0 + HOLD_EXTENSION_MILLIS
    private const val ELASTIC_EXIT_DISTANCE = 0.43
    private const val ELASTIC_DEFORMATION_VELOCITY = 1.2
    private const val ELASTIC_HORIZONTAL_STRETCH = 0.08
    private const val ELASTIC_VERTICAL_STRETCH = 0.11
    private const val ELASTIC_ANTICIPATION = 0.05
    private const val RIBBON_DAMPING_RATIO = 0.62
    private const val RIBBON_SPRING_FREQUENCY = 9.2
    private const val RIBBON_FREQUENCY = 2.5
    private const val RIBBON_ENTRY_DISTANCE = 0.19
    private const val RIBBON_BOB_AMPLITUDE = 0.006
    private const val RIBBON_BOB_FREQUENCY = 1.9
    private const val RIBBON_EXIT_DISTANCE = 0.30
    private const val LANTERN_LIGHT_MOTION_START = 30.0
    private const val LANTERN_ANCHOR_LIFT_START = 1_250.0 + HOLD_EXTENSION_MILLIS
    private const val LANTERN_ANCHOR_FADE_START = 1_450.0 + HOLD_EXTENSION_MILLIS
    private const val LANTERN_ANCHOR_EXIT_DISTANCE = 0.28
    private const val LANTERN_LIGHT_EXIT_START = 1_480.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_CORE_DAMPING_RATIO = 0.76
    private const val ECHO_CORE_SPRING_FREQUENCY = 7.0
    private const val ECHO_CORE_LIVING_START = 760.0
    private const val ECHO_CORE_LIFT_START = 1_450.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_CORE_FADE_START = 1_700.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_CORE_SWAY = 0.012
    private const val ECHO_CORE_BOB = 0.009
    private const val ECHO_CORE_RISE = 0.015
    private const val ECHO_CORE_RISE_START = 760.0
    private const val ECHO_CORE_RISE_DURATION = 650.0
    private const val ECHO_CORE_EXIT_DISTANCE = 0.68
    private const val ECHO_CORE_DIAMETER = 0.282
    private const val ECHO_CORE_FADE_IN_END = 520.0
    private const val ECHO_CORE_ENTRY_SCALE = 0.72
    private const val ECHO_CORE_PULSE_SCALE = 0.038
    private const val ECHO_CORE_BREATHING_SCALE = 0.012
    private const val ECHO_CORE_PULSE_START = 650.0
    private const val ECHO_CORE_PULSE_END = 1_050.0
    private const val ECHO_LEFT_START = 380.0
    private const val ECHO_RIGHT_START = 540.0
    private const val ECHO_LEFT_LIFT_START = 950.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_LEFT_FADE_START = 1_200.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_RIGHT_LIFT_START = 1_200.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_RIGHT_FADE_START = 1_450.0 + HOLD_EXTENSION_MILLIS
    private const val ECHO_PETAL_DAMPING_RATIO = 0.74
    private const val ECHO_PETAL_SPRING_FREQUENCY = 7.4
    private const val ECHO_PETAL_MOTION_LEAD = 195.0
    private const val ECHO_PETAL_LIVING_DELAY = 520.0
    private const val ECHO_PETAL_FADE_DURATION = 520.0
    private const val ECHO_PETAL_START_Y = 0.235
    private const val ECHO_PETAL_ENTRY_Y = 0.30
    private const val ECHO_PETAL_X = 0.18
    private const val ECHO_PETAL_SWAY = 0.018
    private const val ECHO_PETAL_BOB = 0.012
    private const val ECHO_PETAL_EXIT_Y = 0.56
    private const val ECHO_PETAL_DIAMETER = 0.17
    private const val ECHO_PETAL_ENTRY_SCALE = 0.62
    private const val ECHO_PETAL_BREATHING_SCALE = 0.015
    private const val ECHO_LIVING_FREQUENCY = 2.2
    private const val ECHO_LIVING_PHASE = 0.4
    private const val LIVING_BLEND_MILLIS = 260.0
    private const val MILLISECONDS_PER_SECOND = 1_000.0
    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000.0
    private const val SEQUENCE_MIXER = -7046029254386353131L
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val SPLIT_MIX_FIRST = -4658895280553007687L
    private const val SPLIT_MIX_SECOND = -7723592293110705685L
}
