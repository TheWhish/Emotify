package me.whish.emotify.client.custom

import java.nio.file.Path
import me.whish.emotify.domain.EmotionId

data class CustomEmojiReferenceIndex(
    val sourceEmotionIds: Map<Path, EmotionId>,
    val presentEmotionIds: Set<EmotionId>,
    val removalSafe: Boolean,
)

fun reconcileCustomEmojiReferences(
    previousSourceEmotionIds: Map<Path, EmotionId>,
    decodedSourceEmotionIds: Map<Path, EmotionId>,
    unavailableSourcePaths: Set<Path>,
    directoryLimitReached: Boolean,
): CustomEmojiReferenceIndex {
    val previous = previousSourceEmotionIds.entries.associate { (path, emotionId) -> path.normalize() to emotionId }
    val decoded = decodedSourceEmotionIds.entries.associate { (path, emotionId) -> path.normalize() to emotionId }
    val sourceEmotionIds = LinkedHashMap<Path, EmotionId>(
        if (directoryLimitReached) previous.size + decoded.size else decoded.size + unavailableSourcePaths.size,
    )
    if (directoryLimitReached) {
        sourceEmotionIds.putAll(previous)
    }
    sourceEmotionIds.putAll(decoded)
    unavailableSourcePaths.forEach { sourcePath ->
        val normalizedPath = sourcePath.normalize()
        previous[normalizedPath]?.let { emotionId -> sourceEmotionIds.putIfAbsent(normalizedPath, emotionId) }
    }
    return CustomEmojiReferenceIndex(
        java.util.Map.copyOf(sourceEmotionIds),
        java.util.Set.copyOf(sourceEmotionIds.values),
        !directoryLimitReached,
    )
}
