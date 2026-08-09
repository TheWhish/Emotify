package me.whish.emotify.client.custom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

@Suppress("unused")
class GifTimelineNormalizerTest : FunSpec({
    test("keeps an already compatible variable timeline unchanged") {
        val normalized = GifTimelineNormalizer.normalize(intArrayOf(100, 200, 300))

        normalized.map(GifTimelineFrame::sourceIndex) shouldContainExactly listOf(0, 1, 2)
        normalized.map(GifTimelineFrame::durationMillis) shouldContainExactly listOf(100, 200, 300)
    }

    test("resamples high frame rate animation across its complete timeline") {
        val normalized = GifTimelineNormalizer.normalize(IntArray(60) { 10 })

        normalized.size shouldBe 8
        normalized.first().sourceIndex shouldBe 0
        normalized.last().sourceIndex shouldBe 59
        normalized.zipWithNext().all { (left, right) -> left.sourceIndex < right.sourceIndex } shouldBe true
        normalized.sumOf(GifTimelineFrame::durationMillis) shouldBe 600
        normalized.forEach { frame -> frame.durationMillis.shouldBeInRange(67..2_000) }
    }

    test("resampling preserves a long variable frame through the complete emotion lifecycle") {
        val normalized = GifTimelineNormalizer.normalize(intArrayOf(1_000) + IntArray(200) { 10 })

        normalized.first().sourceIndex shouldBe 0
        normalized.first().durationMillis.shouldBeInRange(900..1_100)
        normalized.last().sourceIndex shouldBe 200
        normalized.sumOf(GifTimelineFrame::durationMillis) shouldBe 3_000
        normalized.forEach { frame -> frame.durationMillis.shouldBeInRange(67..2_000) }
    }

    test("thirty four frame animation keeps its complete tempo within the emotion lifecycle") {
        val normalized = GifTimelineNormalizer.normalize(IntArray(34) { 70 })

        normalized.size shouldBe 30
        normalized.first().sourceIndex shouldBe 0
        normalized.last().sourceIndex shouldBe 33
        normalized.sumOf(GifTimelineFrame::durationMillis) shouldBe 2_380
    }

    test("long low frame rate animation is clipped without accelerating its retained frames") {
        val normalized = GifTimelineNormalizer.normalize(IntArray(4) { 1_000 })

        normalized.map(GifTimelineFrame::sourceIndex) shouldContainExactly listOf(0, 1, 2)
        normalized.map(GifTimelineFrame::durationMillis) shouldContainExactly listOf(1_000, 1_000, 1_000)
    }

    test("sub-frame remainder at the lifecycle boundary extends the final retained frame") {
        val normalized = GifTimelineNormalizer.normalize(IntArray(43) { 70 })

        normalized.sumOf(GifTimelineFrame::durationMillis) shouldBe 3_000
        normalized.forEach { frame -> frame.durationMillis.shouldBeInRange(67..2_000) }
    }

    test("consecutive visually identical frames are merged before resampling") {
        val normalized = GifTimelineNormalizer.normalize(
            intArrayOf(100, 200, 100, 100, 100),
        ) { left, right ->
            left / 2 == right / 2
        }

        normalized.map(GifTimelineFrame::sourceIndex) shouldContainExactly listOf(0, 2, 4)
        normalized.map(GifTimelineFrame::durationMillis) shouldContainExactly listOf(300, 200, 100)
    }

    test("very short animation is slowed only enough to remain representable") {
        val normalized = GifTimelineNormalizer.normalize(intArrayOf(10, 10))

        normalized.map(GifTimelineFrame::durationMillis) shouldContainExactly listOf(67, 67)
    }

    test("a frame covering the complete emotion becomes a static retained image") {
        val normalized = GifTimelineNormalizer.normalize(intArrayOf(60_000, 10))

        normalized shouldContainExactly listOf(GifTimelineFrame(0, 0))
    }

    test("source decode frame count remains independently bounded") {
        shouldThrow<IllegalArgumentException> {
            GifTimelineNormalizer.normalize(
                IntArray(GifTimelineNormalizer.MAXIMUM_SOURCE_FRAME_COUNT + 1) { 67 },
            )
        }
    }

    test("normalization invariants hold across bounded source shapes") {
        for (frameCount in 2..GifTimelineNormalizer.MAXIMUM_SOURCE_FRAME_COUNT step 7) {
            val source = IntArray(frameCount) { index ->
                when (index % 5) {
                    0 -> 0
                    1 -> 10
                    2 -> 67
                    3 -> 250
                    else -> 60_000
                }
            }
            val normalized = GifTimelineNormalizer.normalize(source)

            normalized.size.shouldBeInRange(2..30)
            normalized.first().sourceIndex shouldBe 0
            normalized.zipWithNext().all { (left, right) -> left.sourceIndex <= right.sourceIndex } shouldBe true
            normalized.sumOf(GifTimelineFrame::durationMillis).shouldBeInRange(134..3_000)
            normalized.forEach { frame -> frame.durationMillis.shouldBeInRange(67..2_000) }
        }
    }
})
