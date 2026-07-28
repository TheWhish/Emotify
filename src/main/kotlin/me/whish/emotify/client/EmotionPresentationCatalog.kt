package me.whish.emotify.client

import me.whish.emotify.domain.BuiltInEmotionCategory
import me.whish.emotify.domain.BuiltInEmotionDefinition
import me.whish.emotify.domain.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.EmotionSpriteRegion
import net.minecraft.resources.ResourceLocation

data class EmotionPresentation(
    val emotionId: EmotionId,
    val texture: ResourceLocation,
    val translationKey: String,
    val category: String,
    val glyph: String,
    val sourceSlot: Int,
    val region: EmotionSpriteRegion,
)

data class EmotionCategoryPresentation(
    val id: String,
    val translationKey: String,
)

object EmotionPresentationCatalog {
    val categories: List<EmotionCategoryPresentation> = java.util.List.copyOf(
        BuiltInEmotionManifest.categories.map(::createCategoryPresentation),
    )

    val ordered: List<EmotionPresentation> = java.util.List.copyOf(
        BuiltInEmotionManifest.definitions.map(::createPresentation),
    )

    private val byEmotionId: Map<EmotionId, EmotionPresentation> = java.util.Map.copyOf(
        ordered.associateBy(EmotionPresentation::emotionId),
    )
    private val categoryById: Map<String, EmotionCategoryPresentation> = java.util.Map.copyOf(
        categories.associateBy(EmotionCategoryPresentation::id),
    )

    init {
        check(ordered.size == byEmotionId.size) { "Client emotion presentations must have unique IDs" }
        check(categories.size == categoryById.size) { "Client emotion categories must have unique IDs" }
    }

    fun find(emotionId: EmotionId): EmotionPresentation? = byEmotionId[emotionId]

    fun findCategory(id: String): EmotionCategoryPresentation? = categoryById[id]

    private fun createPresentation(definition: BuiltInEmotionDefinition): EmotionPresentation {
        return EmotionPresentation(
            definition.id,
            ResourceLocation.parse(definition.texture),
            definition.translationKey,
            definition.category,
            definition.glyph,
            definition.sourceSlot,
            definition.region,
        )
    }

    private fun createCategoryPresentation(category: BuiltInEmotionCategory): EmotionCategoryPresentation =
        EmotionCategoryPresentation(category.id, category.translationKey)
}
