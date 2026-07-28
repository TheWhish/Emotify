package me.whish.emotify.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader

data class EmotionSpriteRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val textureWidth: Int,
    val textureHeight: Int,
) {
    val u0: Float = x.toFloat() / textureWidth
    val v0: Float = y.toFloat() / textureHeight
    val u1: Float = (x + width).toFloat() / textureWidth
    val v1: Float = (y + height).toFloat() / textureHeight

    init {
        require(textureWidth > 0 && textureHeight > 0) { "Texture dimensions must be positive" }
        require(width in 1..textureWidth && height in 1..textureHeight) {
            "Sprite dimensions must fit inside its texture"
        }
        require(x in 0..textureWidth - width && y in 0..textureHeight - height) {
            "Sprite origin must keep the region inside its texture"
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
        require(texture.substringAfter(':').split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
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
        require(glyph.codePoints().noneMatch(::isUnsafeGlyphCodePoint)) {
            "Emotion glyph cannot contain malformed, whitespace, or bidi-control code points"
        }
        require(sourceSlot >= 0) { "Emotion source slot cannot be negative" }
    }

    companion object {
        private const val MAX_GLYPH_CODE_POINTS = 8
    }
}

object BuiltInEmotionManifest {
    const val SCHEMA_VERSION = 4

    private const val RESOURCE_PATH = "/assets/emotify/emotions.json"
    private val INTEGER_PATTERN = Regex("0|[1-9][0-9]*")
    private val content = loadContent()

    val categories: List<BuiltInEmotionCategory> = content.categories
    val defaultFavoriteIds: List<EmotionId> = content.defaultFavoriteIds
    val definitions: List<BuiltInEmotionDefinition> = content.definitions

    private val categoryById: Map<String, BuiltInEmotionCategory> = java.util.Map.copyOf(
        categories.associateBy(BuiltInEmotionCategory::id),
    )
    private val byId: Map<EmotionId, BuiltInEmotionDefinition> = java.util.Map.copyOf(
        definitions.associateBy(BuiltInEmotionDefinition::id),
    )

    init {
        check(categories.isNotEmpty()) { "Built-in emotion categories cannot be empty" }
        check(categories.size == categoryById.size) { "Built-in emotion manifest contains duplicate categories" }
        check(definitions.isNotEmpty()) { "Built-in emotion manifest cannot be empty" }
        check(definitions.size == byId.size) { "Built-in emotion manifest contains duplicate IDs" }
        check(defaultFavoriteIds.all(byId::containsKey)) { "Default favorite emotions must exist in the built-in catalog" }
        check(categories.map(BuiltInEmotionCategory::translationKey).distinct().size == categories.size) {
            "Built-in emotion manifest contains duplicate category translation keys"
        }
        check(definitions.map(BuiltInEmotionDefinition::translationKey).distinct().size == definitions.size) {
            "Built-in emotion manifest contains duplicate emotion translation keys"
        }
        check(definitions.map(BuiltInEmotionDefinition::glyph).distinct().size == definitions.size) {
            "Built-in emotion manifest contains duplicate glyphs"
        }
        check(definitions.all { definition -> categoryById.containsKey(definition.category) }) {
            "Built-in emotion manifest contains an unknown category"
        }
        check(definitions.map(::textureRegionKey).distinct().size == definitions.size) {
            "Built-in emotion manifest contains overlapping texture regions"
        }
    }

    fun find(id: EmotionId): BuiltInEmotionDefinition? = byId[id]

    fun findCategory(id: String): BuiltInEmotionCategory? = categoryById[id]

    private fun loadContent(): ManifestContent {
        val stream = checkNotNull(BuiltInEmotionManifest::class.java.getResourceAsStream(RESOURCE_PATH)) {
            "Missing built-in emotion manifest: $RESOURCE_PATH"
        }
        val root = stream.use {
            JsonParser.parseReader(InputStreamReader(it, Charsets.UTF_8)).asJsonObject
        }
        check(root.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported built-in emotion manifest schema"
        }
        val categories = java.util.List.copyOf(
            root.requiredArray("categories").mapIndexed { index, element ->
                check(element.isJsonObject) { "Emotion category $index must be an object" }
                val entry = element.asJsonObject
                BuiltInEmotionCategory(
                    id = entry.requiredString("id"),
                    translationKey = entry.requiredString("translationKey"),
                )
            },
        )
        val categoryIds = categories.map(BuiltInEmotionCategory::id).toSet()
        check(categories.size == categoryIds.size) { "Built-in emotion manifest contains duplicate categories" }
        val sourceDefinitions = root.requiredArray("atlases").flatMapIndexed { index, element ->
            check(element.isJsonObject) { "Emotion atlas $index must be an object" }
            definitionsFromAtlas(index, element.asJsonObject, categoryIds)
        }
        val sourceById = sourceDefinitions.associateBy(BuiltInEmotionDefinition::id)
        check(sourceDefinitions.size == sourceById.size) { "Built-in emotion manifest contains duplicate IDs" }
        val defaultFavoriteIds = java.util.List.copyOf(
            root.requiredArray("defaultFavorites").mapIndexed { index, element ->
                check(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    "Default favorite emotion $index must be a string"
                }
                EmotionId.of(element.asString)
            },
        )
        val defaultFavoriteSet = defaultFavoriteIds.toSet()
        check(defaultFavoriteIds.size == defaultFavoriteSet.size) {
            "Built-in emotion manifest contains duplicate default favorite IDs"
        }
        check(defaultFavoriteIds.all(sourceById::containsKey)) { "Default favorite emotions must exist in an atlas" }
        val categoryRank = categories.mapIndexed { index, category -> category.id to index }.toMap()
        val orderedDefinitions = sourceDefinitions.sortedWith(
            compareBy(
                { definition -> categoryRank.getValue(definition.category) },
                { definition -> definition.sourceSlot },
                { definition -> definition.id.value },
            ),
        )
        return ManifestContent(
            categories = categories,
            defaultFavoriteIds = defaultFavoriteIds,
            definitions = java.util.List.copyOf(orderedDefinitions),
        )
    }

    private fun definitionsFromAtlas(
        atlasIndex: Int,
        atlas: JsonObject,
        categoryIds: Set<String>,
    ): List<BuiltInEmotionDefinition> {
        val category = atlas.requiredString("category")
        check(category in categoryIds) { "Emotion atlas $atlasIndex uses unknown category '$category'" }
        val texture = atlas.requiredString("texture")
        val textureWidth = atlas.requiredInt("textureWidth")
        val textureHeight = atlas.requiredInt("textureHeight")
        val spriteWidth = atlas.requiredInt("spriteWidth")
        val spriteHeight = atlas.requiredInt("spriteHeight")
        check(textureWidth > 0 && textureHeight > 0 && spriteWidth > 0 && spriteHeight > 0) {
            "Emotion atlas $atlasIndex dimensions must be positive"
        }
        check(textureWidth % spriteWidth == 0 && textureHeight % spriteHeight == 0) {
            "Emotion atlas $atlasIndex must contain a complete sprite grid"
        }
        val columns = textureWidth / spriteWidth
        val capacity = columns * (textureHeight / spriteHeight)
        val definitions = atlas.requiredArray("emotions").mapIndexed { entryIndex, element ->
            check(element.isJsonObject) { "Emotion atlas $atlasIndex entry $entryIndex must be an object" }
            val entry = element.asJsonObject
            val slot = entry.requiredInt("slot")
            check(slot < capacity) { "Emotion atlas $atlasIndex slot $slot is outside its texture" }
            val id = EmotionId.of(entry.requiredString("id"))
            BuiltInEmotionDefinition(
                id = id,
                texture = texture,
                translationKey = id.translationKey(),
                category = category,
                glyph = entry.requiredString("glyph"),
                sourceSlot = slot,
                region = EmotionSpriteRegion(
                    x = slot % columns * spriteWidth,
                    y = slot / columns * spriteHeight,
                    width = spriteWidth,
                    height = spriteHeight,
                    textureWidth = textureWidth,
                    textureHeight = textureHeight,
                ),
            )
        }
        check(definitions.map(BuiltInEmotionDefinition::sourceSlot).toSet().size == definitions.size) {
            "Emotion atlas $atlasIndex contains duplicate slots"
        }
        return definitions
    }

    private fun EmotionId.translationKey(): String {
        val separator = value.indexOf(':')
        return "emotion.${value.substring(0, separator)}.${value.substring(separator + 1)}"
    }

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val value = get(name)
        check(value != null && value.isJsonArray) { "Manifest field '$name' must be an array" }
        return value.asJsonArray
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)
        check(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Manifest field '$name' must be a string"
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = get(name)
        check(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Manifest field '$name' must be an integer"
        }
        val number = value.asString
        check(INTEGER_PATTERN.matches(number)) { "Manifest field '$name' must be an integer" }
        return number.toInt()
    }

    private data class ManifestContent(
        val categories: List<BuiltInEmotionCategory>,
        val defaultFavoriteIds: List<EmotionId>,
        val definitions: List<BuiltInEmotionDefinition>,
    )

    private fun textureRegionKey(definition: BuiltInEmotionDefinition): String = buildString {
        append(definition.texture)
        append('#')
        append(definition.region.x)
        append(',')
        append(definition.region.y)
        append(',')
        append(definition.region.width)
        append(',')
        append(definition.region.height)
    }
}

private const val BUILT_IN_NAMESPACE = "emotify"
private val BUILT_IN_TEXTURE_PATTERN = Regex("$BUILT_IN_NAMESPACE:textures/emotions/[a-z0-9._/-]+[.]png")
private val TRANSLATION_KEY_PATTERN = Regex("[a-z0-9_.-]+")
private val CATEGORY_PATTERN = Regex("[a-z0-9_.-]+")

private fun isUnsafeGlyphCodePoint(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.SURROGATE.toInt() ||
        Character.isWhitespace(codePoint) ||
        codePoint == 0x061C ||
        codePoint in 0x200E..0x200F ||
        codePoint in 0x202A..0x202E ||
        codePoint in 0x2066..0x2069
