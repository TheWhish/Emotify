package me.whish.emotify.client.custom

import me.whish.emotify.domain.CustomEmojiAsset

data class GifTimelineFrame(
    val sourceIndex: Int,
    val durationMillis: Int,
)

object GifTimelineNormalizer {
    const val MAXIMUM_SOURCE_FRAME_COUNT = 300

    fun normalize(
        sourceDurationsMillis: IntArray,
        equivalent: (Int, Int) -> Boolean = { _, _ -> false },
    ): List<GifTimelineFrame> {
        require(sourceDurationsMillis.isNotEmpty()) { "GIF must contain at least one source frame" }
        require(sourceDurationsMillis.size <= MAXIMUM_SOURCE_FRAME_COUNT) {
            "GIF contains more than $MAXIMUM_SOURCE_FRAME_COUNT source frames"
        }
        if (sourceDurationsMillis.size == 1) {
            return listOf(GifTimelineFrame(0, 0))
        }

        val spans = clipToEmotionLifecycle(mergeEquivalentFrames(sourceDurationsMillis, equivalent))
        if (spans.size == 1) {
            return listOf(GifTimelineFrame(spans.single().sourceIndex, 0))
        }

        val sourceDuration = spans.sumOf(SourceSpan::durationMillis)
        val targetDuration = sourceDuration.coerceAtLeast(MINIMUM_ANIMATED_DURATION_MILLIS.toLong()).toInt()
        val maximumTargetFrames = minOf(
            CustomEmojiAsset.MAXIMUM_FRAME_COUNT,
            targetDuration / CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS,
        ).coerceAtLeast(MINIMUM_ANIMATED_FRAME_COUNT)
        if (spans.size > maximumTargetFrames) {
            return resampleTimeline(spans, maximumTargetFrames, sourceDuration, targetDuration)
        }
        val durations = allocateDurations(
            LongArray(spans.size) { index -> spans[index].durationMillis },
            targetDuration,
        )
        return java.util.List.copyOf(
            spans.indices.map { index ->
                GifTimelineFrame(spans[index].sourceIndex, durations[index])
            },
        )
    }

    private fun mergeEquivalentFrames(
        sourceDurationsMillis: IntArray,
        equivalent: (Int, Int) -> Boolean,
    ): List<SourceSpan> {
        val spans = ArrayList<SourceSpan>(sourceDurationsMillis.size)
        sourceDurationsMillis.forEachIndexed { sourceIndex, rawDuration ->
            val duration = rawDuration.coerceIn(
                MINIMUM_SOURCE_FRAME_DURATION_MILLIS,
                MAXIMUM_SOURCE_FRAME_DURATION_MILLIS,
            ).toLong()
            val previous = spans.lastOrNull()
            if (previous != null && equivalent(previous.sourceIndex, sourceIndex)) {
                spans[spans.lastIndex] = previous.copy(durationMillis = previous.durationMillis + duration)
            } else {
                spans += SourceSpan(sourceIndex, duration)
            }
        }
        return spans
    }

    private fun clipToEmotionLifecycle(spans: List<SourceSpan>): List<SourceSpan> {
        val clipped = ArrayList<SourceSpan>(spans.size)
        var remaining = CustomEmojiAsset.MAXIMUM_CYCLE_DURATION_MILLIS.toLong()
        for (span in spans) {
            if (remaining == 0L) {
                break
            }
            val retainedDuration = minOf(span.durationMillis, remaining)
            if (
                retainedDuration < CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS &&
                span.durationMillis > retainedDuration &&
                clipped.isNotEmpty()
            ) {
                break
            }
            clipped += span.copy(durationMillis = retainedDuration)
            remaining -= retainedDuration
        }
        return clipped
    }

    private fun resampleTimeline(
        spans: List<SourceSpan>,
        targetCount: Int,
        sourceDuration: Long,
        targetDuration: Int,
    ): List<GifTimelineFrame> {
        val cumulativeEnds = LongArray(spans.size)
        var cumulative = 0L
        spans.forEachIndexed { index, span ->
            cumulative += span.durationMillis
            cumulativeEnds[index] = cumulative
        }
        val sampledDurations = allocateDurations(LongArray(targetCount) { 1L }, targetDuration)
        val sampled = ArrayList<GifTimelineFrame>(targetCount)
        repeat(targetCount) { targetIndex ->
            val timelinePosition = targetIndex.toLong() * (sourceDuration - 1L) / (targetCount - 1L)
            val spanIndex = cumulativeEnds.binarySearch(timelinePosition + 1L).let { result ->
                if (result >= 0) result else -result - 1
            }
            sampled += GifTimelineFrame(spans[spanIndex].sourceIndex, sampledDurations[targetIndex])
        }
        val merged = ArrayList<GifTimelineFrame>(sampled.size)
        sampled.forEach { frame ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.sourceIndex == frame.sourceIndex) {
                merged[merged.lastIndex] = previous.copy(durationMillis = previous.durationMillis + frame.durationMillis)
            } else {
                merged += frame
            }
        }
        val normalized = ArrayList<GifTimelineFrame>(merged.size)
        merged.forEach { frame ->
            val partCount = (frame.durationMillis + CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS - 1) /
                CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS
            val partDurations = allocateDurations(LongArray(partCount) { 1L }, frame.durationMillis)
            partDurations.forEach { duration -> normalized += GifTimelineFrame(frame.sourceIndex, duration) }
        }
        return java.util.List.copyOf(normalized)
    }

    private fun allocateDurations(weights: LongArray, targetDuration: Int): IntArray {
        val totalWeight = weights.sum()
        val durations = IntArray(weights.size)
        var cumulativeWeight = 0L
        var assigned = 0
        weights.forEachIndexed { index, weight ->
            cumulativeWeight += weight
            val cumulativeDuration = (cumulativeWeight * targetDuration / totalWeight).toInt()
            durations[index] = cumulativeDuration - assigned
            assigned = cumulativeDuration
        }
        durations.indices.forEach { index ->
            durations[index] = durations[index].coerceIn(
                CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS,
                CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS,
            )
        }
        rebalanceDurations(durations, targetDuration)
        return durations
    }

    private fun rebalanceDurations(durations: IntArray, targetDuration: Int) {
        var difference = targetDuration - durations.sum()
        if (difference > 0) {
            durations.indices.forEach { index ->
                if (difference == 0) {
                    return
                }
                val addition = minOf(
                    difference,
                    CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS - durations[index],
                )
                durations[index] += addition
                difference -= addition
            }
        } else if (difference < 0) {
            for (index in durations.indices.reversed()) {
                if (difference == 0) {
                    return
                }
                val removal = minOf(
                    -difference,
                    durations[index] - CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS,
                )
                durations[index] -= removal
                difference += removal
            }
        }
        check(difference == 0) { "GIF frame durations could not be normalized to $targetDuration ms" }
    }

    private data class SourceSpan(
        val sourceIndex: Int,
        val durationMillis: Long,
    )

    private const val MINIMUM_ANIMATED_FRAME_COUNT = 2
    private const val MINIMUM_ANIMATED_DURATION_MILLIS =
        MINIMUM_ANIMATED_FRAME_COUNT * CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS
    private const val MINIMUM_SOURCE_FRAME_DURATION_MILLIS = 10
    private const val MAXIMUM_SOURCE_FRAME_DURATION_MILLIS = 60_000
}
