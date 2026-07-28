package me.whish.emotify.client

import me.whish.emotify.domain.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import net.neoforged.neoforge.common.ModConfigSpec

object ClientFavoritesConfig {
    private val defaultValues = BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value)
    private val builder = ModConfigSpec.Builder()
    private val favoriteIds = builder.defineListAllowEmpty(
        "favorites",
        defaultValues,
        { defaultValues.first() },
        { value ->
            value is String && EmotionId.parse(value) != null
        },
    )

    val spec: ModConfigSpec = builder.build()

    fun load(): List<EmotionId> = java.util.List.copyOf(
        favoriteIds.get()
            .asSequence()
            .mapNotNull(EmotionId::parse)
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList(),
    )

    fun save(ids: Collection<EmotionId>) {
        favoriteIds.set(
            ids.asSequence()
                .distinct()
                .take(EmotionCatalog.MAX_SIZE)
                .map(EmotionId::value)
                .toList(),
        )
        favoriteIds.save()
    }
}
