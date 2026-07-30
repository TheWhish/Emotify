package me.whish.emotify.client

import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

enum class FavoriteToggleResult {
    ADDED,
    REMOVED,
    UNKNOWN_EMOTION,
}

class FavoriteEmotionStore private constructor(initial: Collection<EmotionId>) {
    private var favorites: Set<EmotionId> = normalized(initial)

    val snapshot: Set<EmotionId>
        get() = favorites

    fun isFavorite(emotionId: EmotionId): Boolean = emotionId in favorites

    fun toggle(emotionId: EmotionId): FavoriteToggleResult {
        if (!BuiltInEmotionCatalog.catalog.contains(emotionId)) {
            return FavoriteToggleResult.UNKNOWN_EMOTION
        }
        val updated = LinkedHashSet(favorites)
        val result = if (updated.remove(emotionId)) {
            FavoriteToggleResult.REMOVED
        } else {
            updated.add(emotionId)
            FavoriteToggleResult.ADDED
        }
        favorites = java.util.Set.copyOf(updated)
        return result
    }

    fun orderedIds(): List<EmotionId> = java.util.List.copyOf(
        buildList(favorites.size) {
            addAll(BuiltInEmotionCatalog.catalog.ids.filter(favorites::contains))
            favorites.asSequence()
                .filterNot(BuiltInEmotionCatalog.catalog::contains)
                .sortedBy(EmotionId::value)
                .forEach(::add)
        },
    )

    companion object {
        fun withDefaults(): FavoriteEmotionStore = from(BuiltInEmotionManifest.defaultFavoriteIds)

        fun from(initial: Collection<EmotionId>): FavoriteEmotionStore = FavoriteEmotionStore(initial)

        private fun normalized(source: Collection<EmotionId>): Set<EmotionId> = java.util.Set.copyOf(
            source.asSequence().distinct().take(EmotionCatalog.MAX_SIZE).toSet(),
        )
    }
}
