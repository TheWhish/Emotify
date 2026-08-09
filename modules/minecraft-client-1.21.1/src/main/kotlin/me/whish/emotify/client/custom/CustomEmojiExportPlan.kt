package me.whish.emotify.client.custom

import kotlin.math.max
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor

enum class CustomEmojiExportFormat {
    PNG,
    GIF,
}

data class CustomEmojiExportPlan(
    val fileName: String,
    val format: CustomEmojiExportFormat,
) {
    companion object {
        fun forAsset(asset: CustomEmojiAsset, descriptor: CustomEmojiDescriptor): CustomEmojiExportPlan {
            val format = if (asset.isAnimated) CustomEmojiExportFormat.GIF else CustomEmojiExportFormat.PNG
            return CustomEmojiExportPlan(
                fileName = "${safeFileStem(descriptor.displayName)}.${format.name.lowercase()}",
                format = format,
            )
        }

        private fun safeFileStem(displayName: String): String {
            val sanitized = buildString(displayName.length) {
                displayName.forEach { character ->
                    append(if (character.isISOControl() || character in FORBIDDEN_FILE_CHARACTERS) '_' else character)
                }
            }.trim { character -> character.isWhitespace() || character == '.' }
            val nonEmpty = sanitized.ifEmpty { FALLBACK_FILE_STEM }
            return if (isWindowsReservedFileStem(nonEmpty)) "_$nonEmpty" else nonEmpty
        }

        private fun isWindowsReservedFileStem(fileStem: String): Boolean =
            fileStem.substringBefore('.').trimEnd().uppercase() in WINDOWS_RESERVED_FILE_STEMS

        private const val FALLBACK_FILE_STEM = "custom_emoji"
        private const val FORBIDDEN_FILE_CHARACTERS = "<>:\"/\\|?*"
        private val WINDOWS_RESERVED_FILE_STEMS = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { index ->
                add("COM$index")
                add("LPT$index")
            }
        }
    }
}

object GifFrameTiming {
    private const val MILLISECONDS_PER_CENTISECOND = 10
    private const val MINIMUM_FRAME_CENTISECONDS = 7

    fun quantizeToCentiseconds(frameDurationsMillis: List<Int>): IntArray {
        require(frameDurationsMillis.isNotEmpty()) { "GIF timing requires at least one frame" }
        require(frameDurationsMillis.all { duration -> duration > 0 }) { "GIF frame durations must be positive" }

        val totalMillis = frameDurationsMillis.sum()
        val minimumTotal = frameDurationsMillis.size * MINIMUM_FRAME_CENTISECONDS
        val targetTotal = max(
            minimumTotal,
            (totalMillis + MILLISECONDS_PER_CENTISECOND / 2) / MILLISECONDS_PER_CENTISECOND,
        )
        val delays = IntArray(frameDurationsMillis.size)
        var sourceCumulative = 0
        var assigned = 0
        frameDurationsMillis.forEachIndexed { index, duration ->
            val remainingFrames = frameDurationsMillis.lastIndex - index
            val minimumRemaining = remainingFrames * MINIMUM_FRAME_CENTISECONDS
            val maximumCurrent = targetTotal - assigned - minimumRemaining
            sourceCumulative += duration
            val proportionalCumulative = (
                sourceCumulative.toLong() * targetTotal + totalMillis / 2
                ) / totalMillis
            val proportionalCurrent = proportionalCumulative.toInt() - assigned
            val delay = proportionalCurrent.coerceIn(MINIMUM_FRAME_CENTISECONDS, maximumCurrent)
            delays[index] = delay
            assigned += delay
        }
        check(assigned == targetTotal) { "GIF frame timing did not preserve its target cycle" }
        return delays
    }
}
