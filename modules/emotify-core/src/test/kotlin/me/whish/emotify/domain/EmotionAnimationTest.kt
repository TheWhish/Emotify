package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThan
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
    val variantSeeds = listOf(0L, 2L, 4L, 6L)

    test("four conceptually distinct variants split deterministic selection equally") {
        EmotionAnimationVariant.entries shouldBe listOf(
            EmotionAnimationVariant.ELASTIC_POP,
            EmotionAnimationVariant.RIBBON_WEAVE,
            EmotionAnimationVariant.LANTERN_RELEASE,
            EmotionAnimationVariant.ECHO_BLOOM,
        )

        val distribution = EmotionAnimationVariant.entries.associateWith { 0 }.toMutableMap()
        repeat(400) { bucket ->
            val variant = EmotionAnimation.variantFor(bucket.toLong() shl 1)
            distribution[variant] = distribution.getValue(variant) + 1
        }

        distribution.values.toSet() shouldBe setOf(100)
    }

    test("choreographies use one three two and three sprite silhouettes") {
        val expectedCounts = listOf(1, 3, 2, 3)
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

    test("three second lifecycle extends only the steady hold and preserves fade envelopes") {
        EmotionAnimation.DURATION_MILLIS shouldBe 3_000.0

        variantSeeds.forEach { seed ->
            val hold = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(2_000.0, AnimationMotion.FULL, seed, hold)
            repeat(hold.spriteCount) { spriteIndex ->
                hold.alphaAt(spriteIndex) shouldBe 1.0
            }
        }

        listOf(
            Triple(0L, 0, 2_690.0),
            Triple(2L, 0, 2_670.0),
            Triple(2L, 1, 2_690.0),
            Triple(2L, 2, 2_710.0),
            Triple(4L, 0, 2_625.0),
            Triple(4L, 1, 2_640.0),
            Triple(6L, 0, 2_750.0),
            Triple(6L, 1, 2_500.0),
            Triple(6L, 2, 2_625.0),
        ).forEach { (seed, spriteIndex, elapsed) ->
            val midpoint = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(elapsed, AnimationMotion.FULL, seed, midpoint)
            midpoint.alphaAt(spriteIndex) shouldBe 0.5
        }

        val reducedMidpoint = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_750.0, AnimationMotion.REDUCED, 0L, reducedMidpoint)
        reducedMidpoint.alphaAt(0) shouldBe 0.5
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
        EmotionAnimation.sampleInto(2_350.0, AnimationMotion.FULL, 0L, exitStart)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 0L, exitEnd)

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

    test("ribbon weave retains a subtle living sway with slightly reduced excursion") {
        val restingOffsets = doubleArrayOf(-0.16, 0.13, -0.055)
        val minimumExcursions = doubleArrayOf(0.0320, 0.0292, 0.0255)
        val maximumExcursions = doubleArrayOf(0.0330, 0.0300, 0.0265)
        val excursions = DoubleArray(EmotionAnimation.MAX_SPRITE_COUNT)
        val frame = EmotionAnimationFrameBuffer()

        var elapsed = 700
        while (elapsed <= 2_300) {
            EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, 2L, frame)
            repeat(frame.spriteCount) { spriteIndex ->
                excursions[spriteIndex] = maxOf(
                    excursions[spriteIndex],
                    abs(frame.horizontalOffsetAt(spriteIndex) - restingOffsets[spriteIndex]),
                )
            }
            elapsed += 5
        }

        excursions.indices.forEach { spriteIndex ->
            (excursions[spriteIndex] in minimumExcursions[spriteIndex]..maximumExcursions[spriteIndex]) shouldBe true
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

    test("ribbon sprites share one upward exit speed despite their stagger") {
        val intervals = listOf(
            Triple(0, 2_650.0, 2_750.0),
            Triple(1, 2_650.0, 2_750.0),
            Triple(2, 2_650.0, 2_750.0),
        )
        val travels = intervals.map { (spriteIndex, start, end) ->
            val earlier = EmotionAnimationFrameBuffer()
            val later = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(start, AnimationMotion.FULL, 2L, earlier)
            EmotionAnimation.sampleInto(end, AnimationMotion.FULL, 2L, later)
            later.verticalOffsetAt(spriteIndex) - earlier.verticalOffsetAt(spriteIndex)
        }

        travels.forEach { travel ->
            (travel > 0.04) shouldBe true
        }
        ((travels.maxOrNull() ?: 0.0) - (travels.minOrNull() ?: 0.0) <= 0.0001) shouldBe true
    }

    test("multi sprite compositions accelerate together through their visible exit") {
        listOf(
            Triple(2L, 2_340.0, 2_800.0),
            Triple(4L, 2_050.0, 2_700.0),
            Triple(6L, 1_750.0, 2_700.0),
        ).forEach { (seed, earlyStart, lateStart) ->
            val early = EmotionAnimationFrameBuffer()
            val earlyEnd = EmotionAnimationFrameBuffer()
            val late = EmotionAnimationFrameBuffer()
            val lateEnd = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(earlyStart, AnimationMotion.FULL, seed, early)
            EmotionAnimation.sampleInto(earlyStart + 100.0, AnimationMotion.FULL, seed, earlyEnd)
            EmotionAnimation.sampleInto(lateStart, AnimationMotion.FULL, seed, late)
            EmotionAnimation.sampleInto(lateStart + 100.0, AnimationMotion.FULL, seed, lateEnd)

            val earlyTravels = List(early.spriteCount) { spriteIndex ->
                earlyEnd.verticalOffsetAt(spriteIndex) - early.verticalOffsetAt(spriteIndex)
            }
            val lateTravels = List(late.spriteCount) { spriteIndex ->
                lateEnd.verticalOffsetAt(spriteIndex) - late.verticalOffsetAt(spriteIndex)
            }

            ((earlyTravels.maxOrNull() ?: 0.0) - (earlyTravels.minOrNull() ?: 0.0) <= 0.0001) shouldBe true
            ((lateTravels.maxOrNull() ?: 0.0) - (lateTravels.minOrNull() ?: 0.0) <= 0.0001) shouldBe true
            (lateTravels.minOrNull() ?: 0.0) shouldBeGreaterThan (earlyTravels.maxOrNull() ?: 0.0) + 0.018
        }
    }

    test("every multi sprite exit shares one visible squash and stretch response") {
        listOf(
            2L to 2_340.0,
            4L to 2_050.0,
            6L to 1_750.0,
        ).forEach { (seed, start) ->
            val launch = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(start + 600.0, AnimationMotion.FULL, seed, launch)
            val horizontalScale = launch.horizontalScaleAt(0)
            val verticalScale = launch.verticalScaleAt(0)

            (verticalScale > 1.045) shouldBe true
            (horizontalScale < 0.970) shouldBe true
            repeat(launch.spriteCount) { spriteIndex ->
                abs(launch.horizontalScaleAt(spriteIndex) - horizontalScale) shouldBeLessThanOrEqual 0.0001
                abs(launch.verticalScaleAt(spriteIndex) - verticalScale) shouldBeLessThanOrEqual 0.0001
            }
        }
    }

    test("multi sprite exits preserve their internal geometry while following a shared curve") {
        listOf(
            Triple(2L, 2_340.0, 2_700.0),
            Triple(4L, 2_050.0, 2_650.0),
            Triple(6L, 1_750.0, 2_500.0),
        ).forEach { (seed, start, curved) ->
            val reference = EmotionAnimationFrameBuffer()
            val flight = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(start, AnimationMotion.FULL, seed, reference)
            EmotionAnimation.sampleInto(curved, AnimationMotion.FULL, seed, flight)
            val sharedHorizontalTravel = flight.horizontalOffsetAt(0) - reference.horizontalOffsetAt(0)

            abs(sharedHorizontalTravel) shouldBeGreaterThan 0.014
            repeat(reference.spriteCount) { spriteIndex ->
                val horizontalTravel = flight.horizontalOffsetAt(spriteIndex) -
                    reference.horizontalOffsetAt(spriteIndex)
                val verticalTravel = flight.verticalOffsetAt(spriteIndex) -
                    reference.verticalOffsetAt(spriteIndex)
                val anchorVerticalTravel = flight.verticalOffsetAt(0) - reference.verticalOffsetAt(0)

                abs(horizontalTravel - sharedHorizontalTravel) shouldBeLessThanOrEqual 0.0001
                abs(verticalTravel - anchorVerticalTravel) shouldBeLessThanOrEqual 0.0001
            }
        }
    }

    test("lantern release hands motion from its lower anchor to one rising light") {
        val earlyHandoff = EmotionAnimationFrameBuffer()
        val handoff = EmotionAnimationFrameBuffer()
        val separation = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(450.0, AnimationMotion.FULL, 4L, earlyHandoff)
        EmotionAnimation.sampleInto(1_000.0, AnimationMotion.FULL, 4L, handoff)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 4L, separation)

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
        (separation.verticalOffsetAt(0) > handoff.verticalOffsetAt(0) + 0.20) shouldBe true
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

    test("both lantern elements keep rising while they fade") {
        val earlyFade = EmotionAnimationFrameBuffer()
        val middleFade = EmotionAnimationFrameBuffer()
        val lateFade = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_500.0, AnimationMotion.FULL, 4L, earlyFade)
        EmotionAnimation.sampleInto(2_700.0, AnimationMotion.FULL, 4L, middleFade)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 4L, lateFade)

        repeat(2) { spriteIndex ->
            (middleFade.verticalOffsetAt(spriteIndex) > earlyFade.verticalOffsetAt(spriteIndex) + 0.035) shouldBe true
            (lateFade.verticalOffsetAt(spriteIndex) > middleFade.verticalOffsetAt(spriteIndex) + 0.035) shouldBe true
            (middleFade.alphaAt(spriteIndex) < earlyFade.alphaAt(spriteIndex)) shouldBe true
            (lateFade.alphaAt(spriteIndex) < middleFade.alphaAt(spriteIndex)) shouldBe true
        }
    }

    test("lantern elements begin their exit together and preserve vertical separation") {
        val exitStart = EmotionAnimationFrameBuffer()
        val earlyFlight = EmotionAnimationFrameBuffer()
        val lateFlight = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_050.0, AnimationMotion.FULL, 4L, exitStart)
        EmotionAnimation.sampleInto(2_250.0, AnimationMotion.FULL, 4L, earlyFlight)
        EmotionAnimation.sampleInto(2_850.0, AnimationMotion.FULL, 4L, lateFlight)

        val anchorEarlyTravel = earlyFlight.verticalOffsetAt(0) - exitStart.verticalOffsetAt(0)
        val lightEarlyTravel = earlyFlight.verticalOffsetAt(1) - exitStart.verticalOffsetAt(1)
        val earlySeparation = earlyFlight.verticalOffsetAt(1) - earlyFlight.verticalOffsetAt(0)
        val lateSeparation = lateFlight.verticalOffsetAt(1) - lateFlight.verticalOffsetAt(0)

        (anchorEarlyTravel > 0.003) shouldBe true
        (lightEarlyTravel > 0.003) shouldBe true
        (abs(anchorEarlyTravel - lightEarlyTravel) <= 0.0001) shouldBe true
        (abs(lateSeparation - earlySeparation) <= 0.0001) shouldBe true
    }

    test("echo bloom unfolds two smaller echoes into a symmetric living fan") {
        val forming = EmotionAnimationFrameBuffer()
        val unfolding = EmotionAnimationFrameBuffer()
        val bloomed = EmotionAnimationFrameBuffer()
        val exitStart = EmotionAnimationFrameBuffer()
        val exitEnd = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(500.0, AnimationMotion.FULL, 6L, forming)
        EmotionAnimation.sampleInto(700.0, AnimationMotion.FULL, 6L, unfolding)
        EmotionAnimation.sampleInto(1_200.0, AnimationMotion.FULL, 6L, bloomed)
        EmotionAnimation.sampleInto(2_100.0, AnimationMotion.FULL, 6L, exitStart)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 6L, exitEnd)

        forming.spriteCount shouldBe 3
        (forming.opacityByteAt(0) > forming.opacityByteAt(1)) shouldBe true
        (forming.opacityByteAt(1) > forming.opacityByteAt(2)) shouldBe true
        (unfolding.alphaAt(1) in 0.50..0.75) shouldBe true
        (unfolding.alphaAt(2) in 0.15..0.40) shouldBe true
        (bloomed.horizontalOffsetAt(1) < -0.14) shouldBe true
        (bloomed.horizontalOffsetAt(2) > 0.14) shouldBe true
        (abs(bloomed.horizontalOffsetAt(1) + bloomed.horizontalOffsetAt(2)) <= 0.01) shouldBe true
        (abs(bloomed.verticalOffsetAt(1) - bloomed.verticalOffsetAt(2)) <= 0.01) shouldBe true
        (bloomed.verticalOffsetAt(1) > bloomed.verticalOffsetAt(0) + 0.11) shouldBe true
        (bloomed.diameterAt(0) >= 0.275) shouldBe true
        (bloomed.diameterAt(1) / bloomed.diameterAt(0) in 0.57..0.64) shouldBe true
        (bloomed.diameterAt(2) / bloomed.diameterAt(0) in 0.57..0.64) shouldBe true
        (abs(exitEnd.horizontalOffsetAt(1) - exitStart.horizontalOffsetAt(1)) <= 0.025) shouldBe true
        (abs(exitEnd.horizontalOffsetAt(2) - exitStart.horizontalOffsetAt(2)) <= 0.025) shouldBe true
        (exitEnd.verticalOffsetAt(0) > exitStart.verticalOffsetAt(0) + 0.25) shouldBe true
        (exitEnd.verticalOffsetAt(1) > exitStart.verticalOffsetAt(1) + 0.35) shouldBe true
        (exitEnd.verticalOffsetAt(2) > exitStart.verticalOffsetAt(2) + 0.35) shouldBe true

        val firstEchoVisibleAt = firstVisibleAt(seed = 6L, spriteIndex = 1)
        val secondEchoVisibleAt = firstVisibleAt(seed = 6L, spriteIndex = 2)
        (firstEchoVisibleAt in 500..520) shouldBe true
        (secondEchoVisibleAt - firstEchoVisibleAt in 140..180) shouldBe true
    }

    test("echo bloom exits in a readable first echo second echo and core sequence") {
        val firstLeaving = EmotionAnimationFrameBuffer()
        val secondLeaving = EmotionAnimationFrameBuffer()
        val coreLeaving = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_175.0, AnimationMotion.FULL, 6L, firstLeaving)
        EmotionAnimation.sampleInto(2_325.0, AnimationMotion.FULL, 6L, secondLeaving)
        EmotionAnimation.sampleInto(2_575.0, AnimationMotion.FULL, 6L, coreLeaving)

        (firstLeaving.alphaAt(1) < 1.0) shouldBe true
        firstLeaving.alphaAt(2) shouldBe 1.0
        firstLeaving.alphaAt(0) shouldBe 1.0
        (secondLeaving.alphaAt(1) < secondLeaving.alphaAt(2)) shouldBe true
        (secondLeaving.alphaAt(2) < 1.0) shouldBe true
        secondLeaving.alphaAt(0) shouldBe 1.0
        (coreLeaving.alphaAt(1) < coreLeaving.alphaAt(2)) shouldBe true
        (coreLeaving.alphaAt(2) < coreLeaving.alphaAt(0)) shouldBe true
        (coreLeaving.alphaAt(0) < 1.0) shouldBe true
    }

    test("echo bloom shared flight begins before every staggered fade") {
        val firstLiftStart = EmotionAnimationFrameBuffer()
        val firstFadeStart = EmotionAnimationFrameBuffer()
        val secondLiftStart = EmotionAnimationFrameBuffer()
        val secondFadeStart = EmotionAnimationFrameBuffer()
        val coreLiftStart = EmotionAnimationFrameBuffer()
        val coreFadeStart = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_750.0, AnimationMotion.FULL, 6L, firstLiftStart)
        EmotionAnimation.sampleInto(2_000.0, AnimationMotion.FULL, 6L, firstFadeStart)
        EmotionAnimation.sampleInto(2_000.0, AnimationMotion.FULL, 6L, secondLiftStart)
        EmotionAnimation.sampleInto(2_250.0, AnimationMotion.FULL, 6L, secondFadeStart)
        EmotionAnimation.sampleInto(2_250.0, AnimationMotion.FULL, 6L, coreLiftStart)
        EmotionAnimation.sampleInto(2_500.0, AnimationMotion.FULL, 6L, coreFadeStart)

        (firstFadeStart.verticalOffsetAt(1) > firstLiftStart.verticalOffsetAt(1) + 0.003) shouldBe true
        (secondFadeStart.verticalOffsetAt(2) > firstLiftStart.verticalOffsetAt(2) + 0.03) shouldBe true
        (coreFadeStart.verticalOffsetAt(0) > firstLiftStart.verticalOffsetAt(0) + 0.08) shouldBe true
        firstFadeStart.alphaAt(1) shouldBe 1.0
        secondFadeStart.alphaAt(2) shouldBe 1.0
        coreFadeStart.alphaAt(0) shouldBe 1.0
    }

    test("echo bloom keeps a bounded curved lateral drift during upward flight") {
        listOf(
            Triple(1, 1_750, 2_900),
            Triple(2, 2_000, 2_900),
            Triple(0, 2_250, 2_900),
        ).forEach { (spriteIndex, liftStart, sampleEnd) ->
            val reference = EmotionAnimationFrameBuffer()
            val frame = EmotionAnimationFrameBuffer()
            EmotionAnimation.sampleInto(liftStart.toDouble(), AnimationMotion.FULL, 6L, reference)
            var maximumDrift = 0.0
            var elapsed = liftStart + 25
            while (elapsed <= sampleEnd) {
                EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, 6L, frame)
                val drift = abs(frame.horizontalOffsetAt(spriteIndex) - reference.horizontalOffsetAt(spriteIndex))
                maximumDrift = maxOf(maximumDrift, drift)
                (drift <= 0.028) shouldBe true
                elapsed += 25
            }
            (maximumDrift >= 0.003) shouldBe true
        }
    }

    test("echo bloom keeps a subtle breathing size cycle after opening") {
        val earlier = EmotionAnimationFrameBuffer()
        val later = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_200.0, AnimationMotion.FULL, 6L, earlier)
        EmotionAnimation.sampleInto(1_650.0, AnimationMotion.FULL, 6L, later)

        (abs(earlier.diameterAt(0) - later.diameterAt(0)) > 0.002) shouldBe true
        (abs(earlier.diameterAt(1) - later.diameterAt(1)) > 0.001) shouldBe true
        (abs(earlier.diameterAt(2) - later.diameterAt(2)) > 0.001) shouldBe true
    }

    test("echo bloom enters with a softer per frame pace than the global motion envelope") {
        var previous = EmotionAnimationFrameBuffer()
        var current = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(0.0, AnimationMotion.FULL, 6L, previous)
        var maximumTranslation = 0.0
        var maximumAlphaChange = 0.0
        var elapsed = 5
        while (elapsed <= 1_100) {
            EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, 6L, current)
            repeat(current.spriteCount) { spriteIndex ->
                maximumTranslation = maxOf(
                    maximumTranslation,
                    hypot(
                        current.horizontalOffsetAt(spriteIndex) - previous.horizontalOffsetAt(spriteIndex),
                        current.verticalOffsetAt(spriteIndex) - previous.verticalOffsetAt(spriteIndex),
                    ),
                )
                maximumAlphaChange = maxOf(
                    maximumAlphaChange,
                    abs(current.alphaAt(spriteIndex) - previous.alphaAt(spriteIndex)),
                )
            }
            val swap = previous
            previous = current
            current = swap
            elapsed += 5
        }

        maximumTranslation shouldBeLessThanOrEqual 0.007
        maximumAlphaChange shouldBeLessThanOrEqual 0.019
    }

    test("echo bloom keeps its longer staggered exit smooth at render sized samples") {
        var previous = EmotionAnimationFrameBuffer()
        var current = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(1_700.0, AnimationMotion.FULL, 6L, previous)
        var maximumTranslation = 0.0
        var maximumAlphaChange = 0.0
        var elapsed = 1_705
        while (elapsed <= 3_000) {
            EmotionAnimation.sampleInto(elapsed.toDouble(), AnimationMotion.FULL, 6L, current)
            repeat(current.spriteCount) { spriteIndex ->
                maximumTranslation = maxOf(
                    maximumTranslation,
                    hypot(
                        current.horizontalOffsetAt(spriteIndex) - previous.horizontalOffsetAt(spriteIndex),
                        current.verticalOffsetAt(spriteIndex) - previous.verticalOffsetAt(spriteIndex),
                    ),
                )
                maximumAlphaChange = maxOf(
                    maximumAlphaChange,
                    abs(current.alphaAt(spriteIndex) - previous.alphaAt(spriteIndex)),
                )
            }
            val swap = previous
            previous = current
            current = swap
            elapsed += 5
        }

        maximumTranslation shouldBeLessThanOrEqual 0.007
        maximumAlphaChange shouldBeLessThanOrEqual 0.019
    }

    test("echo bloom keeps climbing throughout the visible fade") {
        val earlyFade = EmotionAnimationFrameBuffer()
        val middleFade = EmotionAnimationFrameBuffer()
        val lateFade = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_700.0, AnimationMotion.FULL, 6L, earlyFade)
        EmotionAnimation.sampleInto(2_800.0, AnimationMotion.FULL, 6L, middleFade)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 6L, lateFade)

        repeat(3) { spriteIndex ->
            val earlyTravel = middleFade.verticalOffsetAt(spriteIndex) - earlyFade.verticalOffsetAt(spriteIndex)
            val lateTravel = lateFade.verticalOffsetAt(spriteIndex) - middleFade.verticalOffsetAt(spriteIndex)
            (earlyTravel > 0.045) shouldBe true
            (lateTravel > 0.03) shouldBe true
            (lateTravel > earlyTravel) shouldBe true
            (middleFade.alphaAt(spriteIndex) < earlyFade.alphaAt(spriteIndex)) shouldBe true
            (lateFade.alphaAt(spriteIndex) < middleFade.alphaAt(spriteIndex)) shouldBe true
        }
    }

    test("echo bloom gives every sprite the same upward flight speed") {
        val earlier = EmotionAnimationFrameBuffer()
        val later = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_550.0, AnimationMotion.FULL, 6L, earlier)
        EmotionAnimation.sampleInto(2_650.0, AnimationMotion.FULL, 6L, later)
        val travels = List(earlier.spriteCount) { spriteIndex ->
            later.verticalOffsetAt(spriteIndex) - earlier.verticalOffsetAt(spriteIndex)
        }

        travels.forEach { travel ->
            (travel > 0.04) shouldBe true
        }
        ((travels.maxOrNull() ?: 0.0) - (travels.minOrNull() ?: 0.0) <= 0.0001) shouldBe true
    }

    test("echo bloom core cannot catch either earlier echo during exit") {
        val early = EmotionAnimationFrameBuffer()
        val late = EmotionAnimationFrameBuffer()
        EmotionAnimation.sampleInto(2_500.0, AnimationMotion.FULL, 6L, early)
        EmotionAnimation.sampleInto(2_900.0, AnimationMotion.FULL, 6L, late)

        listOf(1, 2).forEach { spriteIndex ->
            val earlyGap = early.verticalOffsetAt(spriteIndex) - early.verticalOffsetAt(0)
            val lateGap = late.verticalOffsetAt(spriteIndex) - late.verticalOffsetAt(0)
            (lateGap >= earlyGap - 0.0001) shouldBe true
        }
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
        val expectedCounts = listOf(1, 3, 2, 3)
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
                Triple(EmotionId.of("emotify:sad"), EmotionAnimationVariant.LANTERN_RELEASE, false),
        )

        fixtures.forEach { (values, expected) ->
            val seed = EmotionAnimation.seedFor(values[0], values[1], values[2], expected.first)
            seed shouldBe values[3]
            EmotionAnimation.variantFor(seed) shouldBe expected.second
            EmotionAnimation.isMirrored(seed) shouldBe expected.third
        }
    }

    test("render time conversion stays tick independent and rejects a backwards clock") {
        EmotionAnimation.elapsedMillis(1_000_000_000L, 4_000_000_000L) shouldBe 3_000.0

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
