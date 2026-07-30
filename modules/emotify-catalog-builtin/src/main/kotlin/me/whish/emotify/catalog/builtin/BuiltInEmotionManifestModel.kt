package me.whish.emotify.catalog.builtin

import java.net.URI
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

data class EmotionSpriteRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val textureWidth: Int,
    val textureHeight: Int,
) {
    init {
        require(textureWidth > 0 && textureHeight > 0) { "Texture dimensions must be positive" }
        require(width in 1..textureWidth && height in 1..textureHeight) {
            "Sprite dimensions must fit inside its texture"
        }
        require(x in 0..textureWidth - width && y in 0..textureHeight - height) {
            "Sprite origin must keep the region inside its texture"
        }
    }

    val u0: Float = x.toFloat() / textureWidth
    val v0: Float = y.toFloat() / textureHeight
    val u1: Float = (x + width).toFloat() / textureWidth
    val v1: Float = (y + height).toFloat() / textureHeight

    internal fun overlaps(other: EmotionSpriteRegion): Boolean =
        x < other.x + other.width &&
            other.x < x + width &&
            y < other.y + other.height &&
            other.y < y + height
}

data class BuiltInEmotionSource(
    val name: String,
    val author: String,
    val url: String,
    val license: String,
) {
    init {
        require(name.isSafeMetadataValue()) { "Invalid built-in emotion source name" }
        require(author.isSafeMetadataValue()) { "Invalid built-in emotion source author" }
        require(LICENSE_PATTERN.matches(license)) { "Invalid built-in emotion source license: '$license'" }
        val sourceUri = runCatching { URI(url) }.getOrNull()
        require(sourceUri?.scheme == "https" && sourceUri.host != null && sourceUri.userInfo == null) {
            "Invalid built-in emotion source URL: '$url'"
        }
    }
}

data class BuiltInEmotionCategory(
    val id: String,
    val translationKey: String,
) {
    init {
        require(CATEGORY_PATTERN.matches(id)) { "Invalid emotion category: '$id'" }
        require(TRANSLATION_KEY_PATTERN.matches(translationKey)) {
            "Invalid category translation key: '$translationKey'"
        }
    }
}

data class BuiltInEmotionDefinition(
    val id: EmotionId,
    val texture: String,
    val translationKey: String,
    val category: String,
    val glyph: String,
    val sourceSlot: Int,
    val region: EmotionSpriteRegion,
) {
    init {
        require(id.value.startsWith("$BUILT_IN_NAMESPACE:")) {
            "Unsupported built-in emotion namespace: '$id'"
        }
        require(BUILT_IN_TEXTURE_PATTERN.matches(texture)) { "Invalid built-in emotion texture: '$texture'" }
        require(texture.substringAfter(':').split('/').none { segment ->
            segment.isEmpty() || segment == "." || segment == ".."
        }) {
            "Unsafe built-in emotion texture path: '$texture'"
        }
        require(TRANSLATION_KEY_PATTERN.matches(translationKey)) {
            "Invalid emotion translation key: '$translationKey'"
        }
        require(CATEGORY_PATTERN.matches(category)) { "Invalid emotion category: '$category'" }
        require(glyph.codePointCount(0, glyph.length) in 1..MAX_GLYPH_CODE_POINTS) {
            "Emotion glyph must contain between 1 and $MAX_GLYPH_CODE_POINTS code points"
        }
        require(glyph.codePoints().noneMatch { codePoint -> Character.isISOControl(codePoint) }) {
            "Emotion glyph cannot contain control characters"
        }
        require(glyph.codePoints().noneMatch { codePoint ->
            Character.isWhitespace(codePoint) || isUnsafeGlyphCodePoint(codePoint)
        }) {
            "Emotion glyph cannot contain malformed, whitespace, or bidi-control code points"
        }
        require(sourceSlot >= 0) { "Emotion source slot cannot be negative" }
    }

    companion object {
        private const val MAX_GLYPH_CODE_POINTS = 8
    }
}

class BuiltInEmotionManifestSnapshot internal constructor(
    val schemaVersion: Int,
    val source: BuiltInEmotionSource,
    categories: Collection<BuiltInEmotionCategory>,
    defaultFavoriteIds: Collection<EmotionId>,
    definitions: Collection<BuiltInEmotionDefinition>,
) {
    val categories: List<BuiltInEmotionCategory> = java.util.List.copyOf(categories)
    val defaultFavoriteIds: List<EmotionId> = java.util.List.copyOf(defaultFavoriteIds)
    val definitions: List<BuiltInEmotionDefinition> = java.util.List.copyOf(definitions)
    val catalog: EmotionCatalog = EmotionCatalog.of(this.definitions.map(BuiltInEmotionDefinition::id))

    private val categoryById: Map<String, BuiltInEmotionCategory> = java.util.Map.copyOf(
        this.categories.associateBy(BuiltInEmotionCategory::id),
    )
    private val byId: Map<EmotionId, BuiltInEmotionDefinition> = java.util.Map.copyOf(
        this.definitions.associateBy(BuiltInEmotionDefinition::id),
    )

    init {
        require(schemaVersion == BuiltInEmotionManifest.SCHEMA_VERSION) {
            "Unsupported built-in emotion manifest schema: $schemaVersion"
        }
        require(this.categories.isNotEmpty()) { "Built-in emotion categories cannot be empty" }
        require(this.categories.size <= MAX_CATEGORIES) {
            "Built-in emotion manifest cannot contain more than $MAX_CATEGORIES categories"
        }
        require(this.categories.size == categoryById.size) {
            "Built-in emotion manifest contains duplicate categories"
        }
        require(this.definitions.isNotEmpty()) { "Built-in emotion manifest cannot be empty" }
        require(this.definitions.size <= EmotionCatalog.MAX_SIZE) {
            "Built-in emotion manifest cannot contain more than ${EmotionCatalog.MAX_SIZE} definitions"
        }
        require(this.definitions.size == byId.size) { "Built-in emotion manifest contains duplicate IDs" }
        require(this.defaultFavoriteIds.size <= MAX_DEFAULT_FAVORITES) {
            "Built-in emotion manifest cannot contain more than $MAX_DEFAULT_FAVORITES default favorites"
        }
        require(this.defaultFavoriteIds.toSet().size == this.defaultFavoriteIds.size) {
            "Built-in emotion manifest contains duplicate default favorite IDs"
        }
        require(this.defaultFavoriteIds.all(byId::containsKey)) {
            "Default favorite emotions must exist in the built-in catalog"
        }
        require(this.categories.map(BuiltInEmotionCategory::translationKey).distinct().size == this.categories.size) {
            "Built-in emotion manifest contains duplicate category translation keys"
        }
        require(this.definitions.map(BuiltInEmotionDefinition::translationKey).distinct().size == this.definitions.size) {
            "Built-in emotion manifest contains duplicate emotion translation keys"
        }
        require(this.definitions.map(BuiltInEmotionDefinition::glyph).distinct().size == this.definitions.size) {
            "Built-in emotion manifest contains duplicate glyphs"
        }
        require(this.definitions.all { definition -> categoryById.containsKey(definition.category) }) {
            "Built-in emotion manifest contains an unknown category"
        }
        require(this.categories.all { category ->
            this.definitions.any { definition -> definition.category == category.id }
        }) {
            "Built-in emotion manifest contains an unused category"
        }
        validateTextureRegions(this.definitions)
    }

    fun find(id: EmotionId): BuiltInEmotionDefinition? = byId[id]

    fun findCategory(id: String): BuiltInEmotionCategory? = categoryById[id]

    private fun validateTextureRegions(definitions: List<BuiltInEmotionDefinition>) {
        definitions.groupBy(BuiltInEmotionDefinition::texture).forEach { (texture, textureDefinitions) ->
            val dimensions = textureDefinitions.map { definition ->
                definition.region.textureWidth to definition.region.textureHeight
            }.distinct()
            require(dimensions.size == 1) { "Built-in emotion texture '$texture' has conflicting dimensions" }
            textureDefinitions.forEachIndexed { index, definition ->
                for (otherIndex in index + 1 until textureDefinitions.size) {
                    require(!definition.region.overlaps(textureDefinitions[otherIndex].region)) {
                        "Built-in emotion texture '$texture' contains overlapping regions"
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_CATEGORIES = 64
        const val MAX_DEFAULT_FAVORITES = 64
    }
}

private const val BUILT_IN_NAMESPACE = "emotify"
private val BUILT_IN_TEXTURE_PATTERN = Regex("$BUILT_IN_NAMESPACE:textures/emotions/[a-z0-9._/-]+[.]png")
private val TRANSLATION_KEY_PATTERN = Regex("[a-z0-9_.-]+")
private val CATEGORY_PATTERN = Regex("[a-z0-9_.-]+")
private val LICENSE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9.+-]{0,63}")

private fun String.isSafeMetadataValue(): Boolean =
    length in 1..256 && isNotBlank() && codePoints().noneMatch { codePoint ->
        Character.isISOControl(codePoint) || isUnsafeGlyphCodePoint(codePoint)
    }

private fun isUnsafeGlyphCodePoint(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.SURROGATE.toInt() ||
        codePoint == 0x061C ||
        codePoint in 0x200E..0x200F ||
        codePoint in 0x202A..0x202E ||
        codePoint in 0x2066..0x2069
