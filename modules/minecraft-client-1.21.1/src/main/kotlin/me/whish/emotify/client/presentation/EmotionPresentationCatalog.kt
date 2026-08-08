package me.whish.emotify.client.presentation

import me.whish.emotify.catalog.builtin.BuiltInEmotionCategory
import me.whish.emotify.catalog.builtin.BuiltInEmotionDefinition
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.domain.EmotionId

data class EmotionPresentation(
    val emotionId: EmotionId,
    val textureId: String,
    val translationKey: String,
    val category: String,
    val glyph: String,
    val sourceSlot: Int,
    val region: EmotionSpriteRegion,
    val literalName: String? = null,
    val textureAnimation: EmotionTextureAnimation? = null,
) {
    fun regionAt(elapsedMillis: Long): EmotionSpriteRegion =
        textureAnimation?.regionAt(elapsedMillis) ?: region
}

data class EmotionTextureFrame(
    val region: EmotionSpriteRegion,
    val durationMillis: Int,
) {
    init {
        require(durationMillis > 0) { "Texture frame duration must be positive: $durationMillis" }
    }
}

class EmotionTextureAnimation(frames: List<EmotionTextureFrame>) {
    val frames: List<EmotionTextureFrame> = java.util.List.copyOf(frames)
    val cycleDurationMillis: Int
    private val cumulativeEnds: IntArray

    init {
        require(this.frames.size > 1) { "Texture animation must contain at least two frames" }
        val textureWidth = this.frames.first().region.textureWidth
        val textureHeight = this.frames.first().region.textureHeight
        require(this.frames.all { frame ->
            frame.region.textureWidth == textureWidth && frame.region.textureHeight == textureHeight
        }) { "Texture animation frames must use the same texture dimensions" }
        cumulativeEnds = IntArray(this.frames.size)
        var duration = 0
        this.frames.indices.forEach { index ->
            duration = Math.addExact(duration, this.frames[index].durationMillis)
            cumulativeEnds[index] = duration
        }
        cycleDurationMillis = duration
    }

    fun regionAt(elapsedMillis: Long): EmotionSpriteRegion {
        val elapsedInCycle = Math.floorMod(elapsedMillis, cycleDurationMillis.toLong()).toInt()
        var low = 0
        var high = cumulativeEnds.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (elapsedInCycle < cumulativeEnds[middle]) {
                high = middle
            } else {
                low = middle + 1
            }
        }
        return frames[low].region
    }
}

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
            definition.texture,
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
