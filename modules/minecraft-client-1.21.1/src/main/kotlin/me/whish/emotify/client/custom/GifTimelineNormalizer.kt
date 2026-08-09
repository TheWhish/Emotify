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

        val spans = collectLifecycleSpans(sourceDurationsMillis, equivalent)
        if (spans.size == 1) {
            return listOf(GifTimelineFrame(spans.single().sourceIndex, 0))
        }

        val sourceDuration = spans.sumOf(SourceSpan::durationMillis)
        val targetDuration = sourceDuration.coerceAtLeast(MINIMUM_ANIMATED_DURATION_MILLIS)
        val maximumTargetFrames = minOf(
            CustomEmojiAsset.MAXIMUM_FRAME_COUNT,
            targetDuration / CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS,
        ).coerceAtLeast(MINIMUM_ANIMATED_FRAME_COUNT)
        val requiredFrameCount = spans.sumOf { span ->
            ceilingDivision(span.durationMillis, CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS)
        }
        if (requiredFrameCount > maximumTargetFrames) {
            return resampleTimeline(spans, maximumTargetFrames, sourceDuration, targetDuration)
        }
        val durations = allocateDurations(spans, targetDuration)
        val retained = ArrayList<GifTimelineFrame>(requiredFrameCount)
        spans.indices.forEach { index ->
            retained += GifTimelineFrame(spans[index].sourceIndex, durations[index])
        }
        return splitOversizedFrames(retained)
    }

    private fun collectLifecycleSpans(
        sourceDurationsMillis: IntArray,
        equivalent: (Int, Int) -> Boolean,
    ): List<SourceSpan> {
        val spans = ArrayList<SourceSpan>(sourceDurationsMillis.size)
        var remaining = CustomEmojiAsset.MAXIMUM_CYCLE_DURATION_MILLIS
        for (sourceIndex in sourceDurationsMillis.indices) {
            val duration = sourceDurationsMillis[sourceIndex].coerceIn(
                MINIMUM_SOURCE_FRAME_DURATION_MILLIS,
                MAXIMUM_SOURCE_FRAME_DURATION_MILLIS,
            )
            val retainedDuration = minOf(duration, remaining)
            if (retainedDuration < CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS && duration > retainedDuration) {
                val previous = spans.lastOrNull()
                if (previous != null) {
                    spans[spans.lastIndex] = previous.copy(durationMillis = previous.durationMillis + retainedDuration)
                    break
                }
            }
            val previous = spans.lastOrNull()
            if (previous != null && equivalent(previous.sourceIndex, sourceIndex)) {
                spans[spans.lastIndex] = previous.copy(durationMillis = previous.durationMillis + retainedDuration)
            } else {
                spans += SourceSpan(sourceIndex, retainedDuration)
            }
            remaining -= retainedDuration
            if (remaining == 0) {
                break
            }
        }
        return spans
    }

    private fun resampleTimeline(
        spans: List<SourceSpan>,
        targetCount: Int,
        sourceDuration: Int,
        targetDuration: Int,
    ): List<GifTimelineFrame> {
        val sampled = ArrayList<GifTimelineFrame>(targetCount)
        var spanIndex = 0
        var spanEnd = spans.first().durationMillis
        repeat(targetCount) { targetIndex ->
            val timelinePosition = targetIndex.toLong() * (sourceDuration - 1L) / (targetCount - 1L)
            while (timelinePosition >= spanEnd && spanIndex < spans.lastIndex) {
                spanIndex++
                spanEnd += spans[spanIndex].durationMillis
            }
            val duration = uniformPartitionSize(targetIndex, targetCount, targetDuration)
            val previous = sampled.lastOrNull()
            if (previous != null && previous.sourceIndex == spans[spanIndex].sourceIndex) {
                sampled[sampled.lastIndex] = previous.copy(durationMillis = previous.durationMillis + duration)
            } else {
                sampled += GifTimelineFrame(spans[spanIndex].sourceIndex, duration)
            }
        }
        return splitOversizedFrames(sampled)
    }

    private fun allocateDurations(spans: List<SourceSpan>, targetDuration: Int): IntArray {
        val totalWeight = spans.sumOf(SourceSpan::durationMillis)
        val durations = IntArray(spans.size)
        var cumulativeWeight = 0
        var assigned = 0
        spans.forEachIndexed { index, span ->
            cumulativeWeight += span.durationMillis
            val cumulativeDuration = (cumulativeWeight.toLong() * targetDuration / totalWeight).toInt()
            durations[index] = cumulativeDuration - assigned
            assigned = cumulativeDuration
        }
        durations.indices.forEach { index ->
            durations[index] = durations[index].coerceAtLeast(CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS)
        }
        rebalanceDurations(durations, targetDuration)
        return durations
    }

    private fun rebalanceDurations(durations: IntArray, targetDuration: Int) {
        var difference = targetDuration - durations.sum()
        if (difference < 0) {
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

    private fun splitOversizedFrames(frames: List<GifTimelineFrame>): List<GifTimelineFrame> {
        if (frames.none { frame -> frame.durationMillis > CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS }) {
            return java.util.List.copyOf(frames)
        }
        val normalized = ArrayList<GifTimelineFrame>(CustomEmojiAsset.MAXIMUM_FRAME_COUNT)
        frames.forEach { frame ->
            val partCount = ceilingDivision(frame.durationMillis, CustomEmojiAsset.MAXIMUM_FRAME_DURATION_MILLIS)
            repeat(partCount) { partIndex ->
                normalized += GifTimelineFrame(
                    frame.sourceIndex,
                    uniformPartitionSize(partIndex, partCount, frame.durationMillis),
                )
            }
        }
        check(normalized.size <= CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
            "GIF normalization exceeded ${CustomEmojiAsset.MAXIMUM_FRAME_COUNT} frames"
        }
        return java.util.List.copyOf(normalized)
    }

    private fun uniformPartitionSize(index: Int, count: Int, total: Int): Int =
        (((index + 1L) * total / count) - (index.toLong() * total / count)).toInt()

    private fun ceilingDivision(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    private data class SourceSpan(
        val sourceIndex: Int,
        val durationMillis: Int,
    )

    private const val MINIMUM_ANIMATED_FRAME_COUNT = 2
    private const val MINIMUM_ANIMATED_DURATION_MILLIS =
        MINIMUM_ANIMATED_FRAME_COUNT * CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS
    private const val MINIMUM_SOURCE_FRAME_DURATION_MILLIS = 10
    private const val MAXIMUM_SOURCE_FRAME_DURATION_MILLIS = 60_000
}
