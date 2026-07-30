package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

@Suppress("unused")
class EmotionAnimationTest : FunSpec({
    val variantSeeds = listOf(0L, 2L, 4L)

    test("three conceptually distinct variants split deterministic selection equally") {
        EmotionAnimationVariant.entries shouldBe listOf(
            EmotionAnimationVariant.ELASTIC_POP,
            EmotionAnimationVariant.RIBBON_WEAVE,
            EmotionAnimationVariant.LANTERN_RELEASE,
        )

        val distribution = EmotionAnimationVariant.entries.associateWith { 0 }.toMutableMap()
        repeat(300) { bucket ->
            val variant = EmotionAnimation.variantFor(bucket.toLong() shl 1)
            distribution[variant] = distribution.getValue(variant) + 1
        }

        distribution.values.toSet() shouldBe setOf(100)
    }

    test("choreographies use one three and two sprite silhouettes") {
        val expectedCounts = listOf(1, 3, 2)
        val signatures = variantSeeds.mapIndexed { variantIndex, seed ->
            val frame = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(1_200.0, AnimationMotion.FULL, seed, frame)

            frame.spriteCount shouldBe expectedCounts[variantIndex]
            List(frame.spriteCount) { spriteIndex ->
                listOf(
                    frame.horizontalOffsetAt(spriteIndex),
                    frame.verticalOffsetAt(spriteIndex),
                    frame.diameterAt(spriteIndex),
                    frame.horizontalScaleAt(spriteIndex),
                    frame.verticalScaleAt(spriteIndex),
                )
            }
        }

        expectedCounts.toSet() shouldBe setOf(1, 2, 3)
        signatures.toSet().size shouldBe EmotionAnimationVariant.entries.size
    }

    test("elastic pop performs a spring jump squash stretch settle and upward finish") {
        val entry = EmotionAnimationFrameBuffer()
        val stretched = EmotionAnimationFrameBuffer()
        val peak = EmotionAnimationFrameBuffer()
        val squashed = EmotionAnimationFrameBuffer()
        val settled = EmotionAnimationFrameBuffer()
        val exitStart = EmotionAnimationFrameBuffer()
        val exitEnd = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(120.0, AnimationMotion.FULL, 0L, entry)
        EmotionAnimation.sampleInto(250.0, AnimationMotion.FULL, 0L, stretched)
        EmotionAnimation.sampleInto(470.0, AnimationMotion.FULL, 0L, peak)
        EmotionAnimation.sampleInto(610.0, AnimationMotion.FULL, 0L, squashed)
        EmotionAnimation.sampleInto(950.0, AnimationMotion.FULL, 0L, settled)
        EmotionAnimation.sampleInto(1_550.0, AnimationMotion.FULL, 0L, exitStart)
        EmotionAnimation.sampleInto(2_100.0, AnimationMotion.FULL, 0L, exitEnd)

        entry.spriteCount shouldBe 1
        (peak.verticalOffsetAt(0) > entry.verticalOffsetAt(0) + 0.22) shouldBe true
        (peak.verticalOffsetAt(0) > settled.verticalOffsetAt(0) + 0.006) shouldBe true
        (stretched.verticalScaleAt(0) > stretched.horizontalScaleAt(0) + 0.035) shouldBe true
        (squashed.horizontalScaleAt(0) > squashed.verticalScaleAt(0) + 0.020) shouldBe true
        (exitEnd.verticalOffsetAt(0) > exitStart.verticalOffsetAt(0) + 0.22) shouldBe true
    }

    test("ribbon weave remains the only compact three sprite ensemble") {
        val frame = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 2L, frame)

        frame.spriteCount shouldBe 3
        (frame.diameterAt(0) >= 0.265) shouldBe true
        (frame.diameterAt(1) >= 0.165) shouldBe true
        (frame.diameterAt(2) >= 0.115) shouldBe true

        var minimumX = Double.POSITIVE_INFINITY
        var maximumX = Double.NEGATIVE_INFINITY
        var minimumY = Double.POSITIVE_INFINITY
        var maximumY = Double.NEGATIVE_INFINITY
        repeat(frame.spriteCount) { spriteIndex ->
            minimumX = minOf(minimumX, frame.horizontalOffsetAt(spriteIndex))
            maximumX = maxOf(maximumX, frame.horizontalOffsetAt(spriteIndex))
            minimumY = minOf(minimumY, frame.verticalOffsetAt(spriteIndex))
            maximumY = maxOf(maximumY, frame.verticalOffsetAt(spriteIndex))
            frame.horizontalScaleAt(spriteIndex) shouldBe 1.0
            frame.verticalScaleAt(spriteIndex) shouldBe 1.0
        }

        (maximumX - minimumX <= 0.46) shouldBe true
        (maximumY - minimumY <= 0.58) shouldBe true
    }

    test("secondary sprites preserve a readable stepped size hierarchy") {
        val ribbon = EmotionAnimationFrameBuffer()
        val lantern = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 2L, ribbon)
        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 4L, lantern)

        (ribbon.diameterAt(1) >= 0.20) shouldBe true
        (ribbon.diameterAt(2) >= 0.155) shouldBe true
        (ribbon.diameterAt(1) / ribbon.diameterAt(0) in 0.72..0.82) shouldBe true
        (ribbon.diameterAt(2) / ribbon.diameterAt(1) in 0.72..0.82) shouldBe true
        (lantern.diameterAt(1) >= 0.195) shouldBe true
        (lantern.diameterAt(1) / lantern.diameterAt(0) in 0.68..0.78) shouldBe true
    }

    test("ribbon weave enters as an evenly timed three step cascade") {
        val visibleTimes = List(EmotionAnimation.MAX_SPRITE_COUNT) { spriteIndex ->
            firstVisibleAt(seed = 2L, spriteIndex = spriteIndex)
        }
        val firstGap = visibleTimes[1] - visibleTimes[0]
        val secondGap = visibleTimes[2] - visibleTimes[1]

        (visibleTimes[0] < visibleTimes[1]) shouldBe true
        (visibleTimes[1] < visibleTimes[2]) shouldBe true
        (firstGap in 160..200) shouldBe true
        (abs(firstGap - secondGap) <= 5) shouldBe true
    }

    test("ribbon weave spreads its resting columns and limits horizontal drift") {
        val resting = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 2L, resting)

        (abs(resting.horizontalOffsetAt(0) - resting.horizontalOffsetAt(1)) >= 0.20) shouldBe true
        (abs(resting.horizontalOffsetAt(1) - resting.horizontalOffsetAt(2)) >= 0.10) shouldBe true

        repeat(EmotionAnimation.MAX_SPRITE_COUNT) { spriteIndex ->
            var minimum = Double.POSITIVE_INFINITY
            var maximum = Double.NEGATIVE_INFINITY
            val frame = EmotionAnimationFrameBuffer()
            var elapsed = 700
            while (elapsed <= 1_500) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, 2L, frame)
                minimum = minOf(minimum, frame.horizontalOffsetAt(spriteIndex))
                maximum = maxOf(maximum, frame.horizontalOffsetAt(spriteIndex))
                elapsed += 5
            }
            (maximum - minimum <= 0.075) shouldBe true
        }
    }

    test("ribbon weave rises past each resting point then settles softly") {
        val motionStarts = listOf(0, 180, 360)
        motionStarts.forEachIndexed { spriteIndex, motionStart ->
            val arrival = EmotionAnimationFrameBuffer()
            val settled = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto((motionStart + 430).toDouble(), AnimationMotion.FULL, 2L, arrival)
            EmotionAnimation.sampleInto((motionStart + 900).toDouble(), AnimationMotion.FULL, 2L, settled)

            (arrival.verticalOffsetAt(spriteIndex) >= settled.verticalOffsetAt(spriteIndex) + 0.008) shouldBe true
        }
    }

    test("lantern release hands motion from a sinking anchor to one rising light") {
        val earlyHandoff = EmotionAnimationFrameBuffer()
        val handoff = EmotionAnimationFrameBuffer()
        val separation = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(450.0, AnimationMotion.FULL, 4L, earlyHandoff)
        EmotionAnimation.sampleInto(1_000.0, AnimationMotion.FULL, 4L, handoff)
        EmotionAnimation.sampleInto(2_100.0, AnimationMotion.FULL, 4L, separation)

        earlyHandoff.spriteCount shouldBe 2
        (earlyHandoff.alphaAt(0) >= MOTION_ALPHA_THRESHOLD) shouldBe true
        (earlyHandoff.alphaAt(1) >= MOTION_ALPHA_THRESHOLD) shouldBe true
        (handoff.opacityByteAt(0) > 0) shouldBe true
        (handoff.opacityByteAt(1) > 0) shouldBe true
        val horizontalSeparation = handoff.horizontalOffsetAt(1) - handoff.horizontalOffsetAt(0)
        val verticalSeparation = handoff.verticalOffsetAt(1) - handoff.verticalOffsetAt(0)
        hypot(
            horizontalSeparation,
            verticalSeparation,
        ) shouldBeLessThanOrEqual 0.40
        (verticalSeparation in 0.345..0.355) shouldBe true
        (separation.verticalOffsetAt(0) < handoff.verticalOffsetAt(0) - 0.045) shouldBe true
        (separation.verticalOffsetAt(1) > handoff.verticalOffsetAt(1) + 0.14) shouldBe true
        (separation.opacityByteAt(0) > 0) shouldBe true
        (separation.opacityByteAt(1) > 0) shouldBe true
    }

    test("lantern handoff begins exactly two hundred milliseconds after its anchor") {
        val anchorVisibleAt = firstVisibleAt(seed = 4L, spriteIndex = 0)
        val lightVisibleAt = firstVisibleAt(seed = 4L, spriteIndex = 1)
        val handoffDelay = lightVisibleAt - anchorVisibleAt

        handoffDelay shouldBe 200
    }

    test("visible motion stays predominantly vertical without broad lateral travel") {
        variantSeeds.flatMap { seed -> listOf(seed, seed or 1L) }.forEach { seed ->
            val minimum = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT) { Double.POSITIVE_INFINITY }
            val maximum = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT) { Double.NEGATIVE_INFINITY }
            val frame = EmotionAnimationFrameBuffer()
            var maximumSpriteCount = 0
            var elapsed = 0
            while (elapsed <= EmotionAnimation.DURATION_MILLIS.toInt()) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, frame)
                maximumSpriteCount = maxOf(maximumSpriteCount, frame.spriteCount)
                repeat(frame.spriteCount) { spriteIndex ->
                    if (frame.alphaAt(spriteIndex) >= MOTION_ALPHA_THRESHOLD) {
                        val horizontal = frame.horizontalOffsetAt(spriteIndex)
                        minimum[spriteIndex] = minOf(minimum[spriteIndex], horizontal)
                        maximum[spriteIndex] = maxOf(maximum[spriteIndex], horizontal)
                        (abs(horizontal) <= MAX_VISIBLE_HORIZONTAL_OFFSET) shouldBe true
                    }
                }
                elapsed += 5
            }
            repeat(maximumSpriteCount) { spriteIndex ->
                withClue("seed=$seed sprite=$spriteIndex") {
                    (maximum[spriteIndex] - minimum[spriteIndex] <= MAX_VISIBLE_HORIZONTAL_TRAVEL) shouldBe true
                }
            }
        }
    }

    test("every visible role keeps a living curved path without a static hold") {
        variantSeeds.forEach { seed ->
            var previous = EmotionAnimationFrameBuffer()
            var midpoint = EmotionAnimationFrameBuffer()
            var current = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(700.0, AnimationMotion.FULL, seed, previous)
            var elapsed = 800
            while (elapsed <= 1_600) {
                EmotionAnimation.sampleInto(elapsed - 50.0, AnimationMotion.FULL, seed, midpoint)
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, current)
                repeat(current.spriteCount) { spriteIndex ->
                    if (
                        previous.alphaAt(spriteIndex) >= MOTION_ALPHA_THRESHOLD &&
                        midpoint.alphaAt(spriteIndex) >= MOTION_ALPHA_THRESHOLD &&
                        current.alphaAt(spriteIndex) >= MOTION_ALPHA_THRESHOLD
                    ) {
                        val travel = hypot(
                            midpoint.horizontalOffsetAt(spriteIndex) - previous.horizontalOffsetAt(spriteIndex),
                            midpoint.verticalOffsetAt(spriteIndex) - previous.verticalOffsetAt(spriteIndex),
                        ) + hypot(
                            current.horizontalOffsetAt(spriteIndex) - midpoint.horizontalOffsetAt(spriteIndex),
                            current.verticalOffsetAt(spriteIndex) - midpoint.verticalOffsetAt(spriteIndex),
                        )
                        withClue("seed=$seed elapsed=$elapsed sprite=$spriteIndex travel=$travel") {
                            (travel >= MINIMUM_TRAVEL_PER_100_MILLIS) shouldBe true
                        }
                    }
                }
                val oldest = previous
                previous = current
                current = oldest
                elapsed += 100
            }
        }
    }

    test("visible sprites preserve whitespace through the full timeline") {
        variantSeeds.flatMap { seed -> listOf(seed, seed or 1L) }.forEach { seed ->
            val frame = EmotionAnimationFrameBuffer()
            var elapsed = 0
            while (elapsed <= EmotionAnimation.DURATION_MILLIS.toInt()) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, frame)
                withClue("seed=$seed elapsed=$elapsed") {
                    assertVisibleClearance(frame)
                }
                elapsed += 5
            }
        }
    }

    test("translation deformation and fades remain continuous without frame jumps") {
        variantSeeds.flatMap { seed -> listOf(seed, seed or 1L) }.forEach { seed ->
            var previous = EmotionAnimationFrameBuffer()
            var current = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(0.0, AnimationMotion.FULL, seed, previous)

            var elapsed = 1
            while (elapsed <= EmotionAnimation.DURATION_MILLIS.toInt()) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, current)
                current.spriteCount shouldBe previous.spriteCount
                repeat(current.spriteCount) { spriteIndex ->
                    val translationDelta = hypot(
                        current.horizontalOffsetAt(spriteIndex) - previous.horizontalOffsetAt(spriteIndex),
                        current.verticalOffsetAt(spriteIndex) - previous.verticalOffsetAt(spriteIndex),
                    )
                    withClue("seed=$seed elapsed=$elapsed sprite=$spriteIndex translation=$translationDelta") {
                        if (
                            previous.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD ||
                            current.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD
                        ) {
                            (translationDelta <= MAX_TRANSLATION_PER_MILLISECOND) shouldBe true
                        }
                        (
                            abs(current.diameterAt(spriteIndex) - previous.diameterAt(spriteIndex)) <=
                                MAX_DIAMETER_CHANGE_PER_MILLISECOND
                            ) shouldBe true
                        (
                            abs(current.horizontalScaleAt(spriteIndex) - previous.horizontalScaleAt(spriteIndex)) <=
                                MAX_SCALE_CHANGE_PER_MILLISECOND
                            ) shouldBe true
                        (
                            abs(current.verticalScaleAt(spriteIndex) - previous.verticalScaleAt(spriteIndex)) <=
                                MAX_SCALE_CHANGE_PER_MILLISECOND
                            ) shouldBe true
                        (
                            abs(current.alphaAt(spriteIndex) - previous.alphaAt(spriteIndex)) <=
                                MAX_ALPHA_CHANGE_PER_MILLISECOND
                            ) shouldBe true
                    }
                }
                val swap = previous
                previous = current
                current = swap
                elapsed++
            }
        }
    }

    test("visible motion preserves velocity continuity across phase boundaries") {
        variantSeeds.flatMap { seed -> listOf(seed, seed or 1L) }.forEach { seed ->
            var twoFramesBack = EmotionAnimationFrameBuffer()
            var previous = EmotionAnimationFrameBuffer()
            var current = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(0.0, AnimationMotion.FULL, seed, twoFramesBack)
            EmotionAnimation.sampleInto(1.0, AnimationMotion.FULL, seed, previous)

            var elapsed = 2
            while (elapsed <= EmotionAnimation.DURATION_MILLIS.toInt()) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, current)
                repeat(current.spriteCount) { spriteIndex ->
                    if (
                        twoFramesBack.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD &&
                        previous.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD &&
                        current.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD
                    ) {
                        val acceleration = hypot(
                            current.horizontalOffsetAt(spriteIndex) -
                                2.0 * previous.horizontalOffsetAt(spriteIndex) +
                                twoFramesBack.horizontalOffsetAt(spriteIndex),
                            current.verticalOffsetAt(spriteIndex) -
                                2.0 * previous.verticalOffsetAt(spriteIndex) +
                                twoFramesBack.verticalOffsetAt(spriteIndex),
                        )
                        withClue("seed=$seed elapsed=$elapsed sprite=$spriteIndex acceleration=$acceleration") {
                            (acceleration <= MAX_ACCELERATION_PER_MILLISECOND_SQUARED) shouldBe true
                        }
                    }
                }
                val oldest = twoFramesBack
                twoFramesBack = previous
                previous = current
                current = oldest
                elapsed++
            }
        }
    }

    test("reusing a frame clears sprites and deformation unused by the next concept") {
        val frame = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 2L, frame)
        frame.spriteCount shouldBe 3
        (frame.opacityByteAt(2) > 0) shouldBe true

        EmotionAnimation.sampleInto(1_100.0, AnimationMotion.FULL, 0L, frame)

        frame.spriteCount shouldBe 1
        frame.opacityByteAt(1) shouldBe 0
        frame.opacityByteAt(2) shouldBe 0
        frame.horizontalScaleAt(1) shouldBe 1.0
        frame.verticalScaleAt(2) shouldBe 1.0
    }

    test("reduced motion keeps each distinct composition readable and still") {
        val expectedCounts = listOf(1, 3, 2)
        variantSeeds.forEachIndexed { variantIndex, seed ->
            val earlier = EmotionAnimationFrameBuffer()
            val later = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(700.0, AnimationMotion.REDUCED, seed, earlier)
            EmotionAnimation.sampleInto(1_300.0, AnimationMotion.REDUCED, seed, later)

            earlier.spriteCount shouldBe expectedCounts[variantIndex]
            later.spriteCount shouldBe expectedCounts[variantIndex]
            repeat(earlier.spriteCount) { spriteIndex ->
                earlier.horizontalOffsetAt(spriteIndex) shouldBe later.horizontalOffsetAt(spriteIndex)
                earlier.verticalOffsetAt(spriteIndex) shouldBe later.verticalOffsetAt(spriteIndex)
                earlier.diameterAt(spriteIndex) shouldBe later.diameterAt(spriteIndex)
                earlier.horizontalScaleAt(spriteIndex) shouldBe 1.0
                earlier.verticalScaleAt(spriteIndex) shouldBe 1.0
                (earlier.opacityByteAt(spriteIndex) > 0) shouldBe true
                (later.opacityByteAt(spriteIndex) > 0) shouldBe true
            }
            assertVisibleClearance(earlier)
        }
    }

    test("all sampled values remain finite and inside the render envelope") {
        checkAll(3_000, Arb.double(-5_000.0..10_000.0), Arb.long()) { elapsed, seed ->
            val frame = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(elapsed, AnimationMotion.FULL, seed, frame)

            (frame.spriteCount in 1..EmotionAnimation.MAX_SPRITE_COUNT) shouldBe true
            repeat(EmotionAnimation.MAX_SPRITE_COUNT) { spriteIndex ->
                val horizontal = frame.horizontalOffsetAt(spriteIndex)
                val vertical = frame.verticalOffsetAt(spriteIndex)
                val diameter = frame.diameterAt(spriteIndex)
                val horizontalScale = frame.horizontalScaleAt(spriteIndex)
                val verticalScale = frame.verticalScaleAt(spriteIndex)
                val alpha = frame.alphaAt(spriteIndex)

                horizontal.isFinite() shouldBe true
                vertical.isFinite() shouldBe true
                diameter.isFinite() shouldBe true
                horizontalScale.isFinite() shouldBe true
                verticalScale.isFinite() shouldBe true
                alpha.isFinite() shouldBe true
                (horizontal in -EmotionAnimation.MAX_HORIZONTAL_OFFSET_BLOCKS..
                    EmotionAnimation.MAX_HORIZONTAL_OFFSET_BLOCKS) shouldBe true
                (vertical in EmotionAnimation.MIN_VERTICAL_OFFSET_BLOCKS..
                    EmotionAnimation.MAX_VERTICAL_OFFSET_BLOCKS) shouldBe true
                (diameter in EmotionAnimation.MIN_DIAMETER_BLOCKS..
                    EmotionAnimation.MAX_DIAMETER_BLOCKS) shouldBe true
                (horizontalScale in EmotionAnimation.MIN_RENDER_SCALE..EmotionAnimation.MAX_RENDER_SCALE) shouldBe true
                (verticalScale in EmotionAnimation.MIN_RENDER_SCALE..EmotionAnimation.MAX_RENDER_SCALE) shouldBe true
                (alpha in 0.0..1.0) shouldBe true
                (frame.opacityByteAt(spriteIndex) in 0..255) shouldBe true
                if (spriteIndex < frame.spriteCount && frame.opacityByteAt(spriteIndex) > 0) {
                    (
                        diameter * verticalScale * 0.5 - vertical <=
                            EmotionAnimation.MAX_BOTTOM_EXTENT_BLOCKS
                        ) shouldBe true
                }
            }
        }
    }

    test("timeline endpoints are safe and completely transparent") {
        val frame = EmotionAnimationFrameBuffer()
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.0).forEach { elapsed ->
            EmotionAnimation.sampleInto(elapsed, AnimationMotion.FULL, 0L, frame)
            repeat(EmotionAnimation.MAX_SPRITE_COUNT) { spriteIndex ->
                frame.opacityByteAt(spriteIndex) shouldBe 0
            }
        }

        listOf(EmotionAnimation.DURATION_MILLIS, Double.POSITIVE_INFINITY).forEach { elapsed ->
            EmotionAnimation.sampleInto(elapsed, AnimationMotion.FULL, 0L, frame)
            repeat(EmotionAnimation.MAX_SPRITE_COUNT) { spriteIndex ->
                frame.opacityByteAt(spriteIndex) shouldBe 0
            }
        }
        EmotionAnimation.isFinished(EmotionAnimation.DURATION_MILLIS) shouldBe true
    }

    test("seed golden vectors remain stable across clients and loaders") {
        val fixtures = listOf(
            listOf(
                0x0011223344556677L,
                -0x7766554433221101L,
                42L,
                1_923_995_509_945_993_973L,
            ) to Triple(EmotionId.of("emotify:happy"), EmotionAnimationVariant.LANTERN_RELEASE, true),
            listOf(1L, 2L, 1L, -7_107_885_269_632_063_644L) to
                Triple(EmotionId.of("emotify:happy"), EmotionAnimationVariant.LANTERN_RELEASE, false),
            listOf(-1L, -2L, 128L, 508_363_006_929_072_852L) to
                Triple(EmotionId.of("emotify:sad"), EmotionAnimationVariant.ELASTIC_POP, false),
        )

        fixtures.forEach { (values, expected) ->
            val seed = EmotionAnimation.seedFor(values[0], values[1], values[2], expected.first)
            seed shouldBe values[3]
            EmotionAnimation.variantFor(seed) shouldBe expected.second
            EmotionAnimation.isMirrored(seed) shouldBe expected.third
        }
    }

    test("render time conversion stays tick independent and rejects a backwards clock") {
        EmotionAnimation.elapsedMillis(1_000_000_000L, 3_200_000_000L) shouldBe 2_200.0

        shouldThrow<IllegalArgumentException> {
            EmotionAnimation.elapsedMillis(2L, 1L)
        }
    }
})

private fun assertVisibleClearance(frame: EmotionAnimationFrameBuffer) {
    var first = 0
    while (first < frame.spriteCount) {
        if (frame.alphaAt(first) >= CLEARANCE_ALPHA_THRESHOLD) {
            var second = first + 1
            while (second < frame.spriteCount) {
                if (frame.alphaAt(second) >= CLEARANCE_ALPHA_THRESHOLD) {
                    val halfWidthSum = (
                        frame.diameterAt(first) * frame.horizontalScaleAt(first) +
                            frame.diameterAt(second) * frame.horizontalScaleAt(second)
                        ) * 0.5
                    val halfHeightSum = (
                        frame.diameterAt(first) * frame.verticalScaleAt(first) +
                            frame.diameterAt(second) * frame.verticalScaleAt(second)
                        ) * 0.5
                    val horizontalClearance =
                        abs(frame.horizontalOffsetAt(second) - frame.horizontalOffsetAt(first)) - halfWidthSum
                    val verticalClearance =
                        abs(frame.verticalOffsetAt(second) - frame.verticalOffsetAt(first)) - halfHeightSum
                    val clearance = max(horizontalClearance, verticalClearance)
                    withClue(
                        "sprites=$first,$second horizontal=$horizontalClearance vertical=$verticalClearance",
                    ) {
                        (clearance >= MINIMUM_CLEARANCE_BLOCKS) shouldBe true
                    }
                }
                second++
            }
        }
        first++
    }
}

private fun firstVisibleAt(
    seed: Long,
    spriteIndex: Int,
): Int {
    val frame = EmotionAnimationFrameBuffer()
    var elapsed = 0
    while (elapsed <= EmotionAnimation.DURATION_MILLIS.toInt()) {
        EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, seed, frame)
        if (frame.alphaAt(spriteIndex) >= CLEARANCE_ALPHA_THRESHOLD) {
            return elapsed
        }
        elapsed += 5
    }
    return Int.MAX_VALUE
}

private const val CLEARANCE_ALPHA_THRESHOLD = 0.10
private const val MOTION_ALPHA_THRESHOLD = 0.50
private const val MINIMUM_CLEARANCE_BLOCKS = 0.025
private const val MINIMUM_TRAVEL_PER_100_MILLIS = 0.0004
private const val MAX_VISIBLE_HORIZONTAL_OFFSET = 0.27
private const val MAX_VISIBLE_HORIZONTAL_TRAVEL = 0.20
private const val MAX_TRANSLATION_PER_MILLISECOND = 0.003
private const val MAX_ACCELERATION_PER_MILLISECOND_SQUARED = 0.00008
private const val MAX_DIAMETER_CHANGE_PER_MILLISECOND = 0.001
private const val MAX_SCALE_CHANGE_PER_MILLISECOND = 0.0025
private const val MAX_ALPHA_CHANGE_PER_MILLISECOND = 0.007
