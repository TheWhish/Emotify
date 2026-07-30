package me.whish.emotify.catalog.builtin

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

class BuiltInEmotionManifestFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object BuiltInEmotionManifestLoader {
    const val MAX_MANIFEST_BYTES = 65_536
    const val MAX_ATLASES = 64
    const val MAX_TEXTURE_DIMENSION = 16_384

    private const val MAX_STRING_LENGTH = 512
    private val INTEGER_PATTERN = Regex("0|[1-9][0-9]*")
    private val ROOT_FIELDS = setOf("schemaVersion", "source", "categories", "defaultFavorites", "atlases")
    private val SOURCE_FIELDS = setOf("name", "author", "url", "license")
    private val CATEGORY_FIELDS = setOf("id", "translationKey")
    private val ATLAS_FIELDS = setOf(
        "category",
        "texture",
        "textureWidth",
        "textureHeight",
        "spriteWidth",
        "spriteHeight",
        "emotions",
    )
    private val EMOTION_FIELDS = setOf("slot", "id", "glyph")

    fun load(input: InputStream): BuiltInEmotionManifestSnapshot {
        return try {
            val bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1)
            formatRequire(bytes.size <= MAX_MANIFEST_BYTES) {
                "Built-in emotion manifest exceeds $MAX_MANIFEST_BYTES bytes"
            }
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            JsonReader(StringReader(text)).use(::readManifest)
        } catch (exception: BuiltInEmotionManifestFormatException) {
            throw exception
        } catch (exception: Exception) {
            val detail = exception.message?.takeIf(String::isNotBlank)
            val message = detail?.let { "Invalid built-in emotion manifest: $it" }
                ?: "Invalid built-in emotion manifest"
            throw BuiltInEmotionManifestFormatException(message, exception)
        }
    }

    fun loadResource(anchor: Class<*>, path: String): BuiltInEmotionManifestSnapshot {
        formatRequire(path.startsWith('/') && ".." !in path) { "Unsafe built-in emotion resource path: '$path'" }
        val stream = anchor.getResourceAsStream(path)
            ?: throw BuiltInEmotionManifestFormatException("Missing built-in emotion manifest: $path")
        return stream.use(::load)
    }

    private fun readManifest(reader: JsonReader): BuiltInEmotionManifestSnapshot {
        reader.isLenient = false
        reader.expect(JsonToken.BEGIN_OBJECT, "Manifest root must be an object")
        var schemaVersion: Int? = null
        var source: BuiltInEmotionSource? = null
        var categories: List<BuiltInEmotionCategory>? = null
        var defaultFavoriteIds: List<EmotionId>? = null
        var atlases: List<ParsedAtlas>? = null
        val fields = HashSet<String>(ROOT_FIELDS.size)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            reader.requireKnownUniqueField(name, ROOT_FIELDS, fields)
            when (name) {
                "schemaVersion" -> schemaVersion = reader.readInt("schemaVersion")
                "source" -> source = reader.readSource()
                "categories" -> categories = reader.readCategories()
                "defaultFavorites" -> defaultFavoriteIds = reader.readDefaultFavorites()
                "atlases" -> atlases = reader.readAtlases()
            }
        }
        reader.endObject()
        requireCompleteFields(fields, ROOT_FIELDS, "manifest")
        reader.expect(JsonToken.END_DOCUMENT, "Built-in emotion manifest contains trailing JSON data")
        return createSnapshot(
            requireNotNull(schemaVersion),
            requireNotNull(source),
            requireNotNull(categories),
            requireNotNull(defaultFavoriteIds),
            requireNotNull(atlases),
        )
    }

    private fun JsonReader.readSource(): BuiltInEmotionSource {
        expect(JsonToken.BEGIN_OBJECT, "Manifest field 'source' must be an object")
        var name: String? = null
        var author: String? = null
        var url: String? = null
        var license: String? = null
        val fields = HashSet<String>(SOURCE_FIELDS.size)
        beginObject()
        while (hasNext()) {
            val field = nextName()
            requireKnownUniqueField(field, SOURCE_FIELDS, fields)
            when (field) {
                "name" -> name = readString("source.name")
                "author" -> author = readString("source.author")
                "url" -> url = readString("source.url")
                "license" -> license = readString("source.license")
            }
        }
        endObject()
        requireCompleteFields(fields, SOURCE_FIELDS, "source")
        return BuiltInEmotionSource(
            requireNotNull(name),
            requireNotNull(author),
            requireNotNull(url),
            requireNotNull(license),
        )
    }

    private fun JsonReader.readCategories(): List<BuiltInEmotionCategory> {
        expect(JsonToken.BEGIN_ARRAY, "Manifest field 'categories' must be an array")
        val result = ArrayList<BuiltInEmotionCategory>()
        beginArray()
        while (hasNext()) {
            formatRequire(result.size < BuiltInEmotionManifestSnapshot.MAX_CATEGORIES) {
                "Built-in emotion manifest contains too many categories"
            }
            result.add(readCategory(result.size))
        }
        endArray()
        return java.util.List.copyOf(result)
    }

    private fun JsonReader.readCategory(index: Int): BuiltInEmotionCategory {
        expect(JsonToken.BEGIN_OBJECT, "Emotion category $index must be an object")
        var id: String? = null
        var translationKey: String? = null
        val fields = HashSet<String>(CATEGORY_FIELDS.size)
        beginObject()
        while (hasNext()) {
            val field = nextName()
            requireKnownUniqueField(field, CATEGORY_FIELDS, fields)
            when (field) {
                "id" -> id = readString("categories[$index].id")
                "translationKey" -> translationKey = readString("categories[$index].translationKey")
            }
        }
        endObject()
        requireCompleteFields(fields, CATEGORY_FIELDS, "categories[$index]")
        return BuiltInEmotionCategory(requireNotNull(id), requireNotNull(translationKey))
    }

    private fun JsonReader.readDefaultFavorites(): List<EmotionId> {
        expect(JsonToken.BEGIN_ARRAY, "Manifest field 'defaultFavorites' must be an array")
        val result = ArrayList<EmotionId>()
        beginArray()
        while (hasNext()) {
            formatRequire(result.size < BuiltInEmotionManifestSnapshot.MAX_DEFAULT_FAVORITES) {
                "Built-in emotion manifest contains too many default favorites"
            }
            result.add(EmotionId.of(readString("defaultFavorites[${result.size}]")))
        }
        endArray()
        return java.util.List.copyOf(result)
    }

    private fun JsonReader.readAtlases(): List<ParsedAtlas> {
        expect(JsonToken.BEGIN_ARRAY, "Manifest field 'atlases' must be an array")
        val result = ArrayList<ParsedAtlas>()
        beginArray()
        while (hasNext()) {
            formatRequire(result.size < MAX_ATLASES) { "Built-in emotion manifest contains too many atlases" }
            result.add(readAtlas(result.size))
        }
        endArray()
        return java.util.List.copyOf(result)
    }

    private fun JsonReader.readAtlas(index: Int): ParsedAtlas {
        expect(JsonToken.BEGIN_OBJECT, "Emotion atlas $index must be an object")
        var category: String? = null
        var texture: String? = null
        var textureWidth: Int? = null
        var textureHeight: Int? = null
        var spriteWidth: Int? = null
        var spriteHeight: Int? = null
        var emotions: List<ParsedEmotion>? = null
        val fields = HashSet<String>(ATLAS_FIELDS.size)
        beginObject()
        while (hasNext()) {
            val field = nextName()
            requireKnownUniqueField(field, ATLAS_FIELDS, fields)
            when (field) {
                "category" -> category = readString("atlases[$index].category")
                "texture" -> texture = readString("atlases[$index].texture")
                "textureWidth" -> textureWidth = readInt("atlases[$index].textureWidth")
                "textureHeight" -> textureHeight = readInt("atlases[$index].textureHeight")
                "spriteWidth" -> spriteWidth = readInt("atlases[$index].spriteWidth")
                "spriteHeight" -> spriteHeight = readInt("atlases[$index].spriteHeight")
                "emotions" -> emotions = readEmotions(index)
            }
        }
        endObject()
        requireCompleteFields(fields, ATLAS_FIELDS, "atlases[$index]")
        return ParsedAtlas(
            requireNotNull(category),
            requireNotNull(texture),
            requireNotNull(textureWidth),
            requireNotNull(textureHeight),
            requireNotNull(spriteWidth),
            requireNotNull(spriteHeight),
            requireNotNull(emotions),
        )
    }

    private fun JsonReader.readEmotions(atlasIndex: Int): List<ParsedEmotion> {
        expect(JsonToken.BEGIN_ARRAY, "Manifest field 'atlases[$atlasIndex].emotions' must be an array")
        val result = ArrayList<ParsedEmotion>()
        beginArray()
        while (hasNext()) {
            formatRequire(result.size < EmotionCatalog.MAX_SIZE) {
                "Emotion atlas $atlasIndex contains too many definitions"
            }
            result.add(readEmotion(atlasIndex, result.size))
        }
        endArray()
        return java.util.List.copyOf(result)
    }

    private fun JsonReader.readEmotion(atlasIndex: Int, entryIndex: Int): ParsedEmotion {
        expect(JsonToken.BEGIN_OBJECT, "Emotion atlas $atlasIndex entry $entryIndex must be an object")
        var slot: Int? = null
        var id: String? = null
        var glyph: String? = null
        val fields = HashSet<String>(EMOTION_FIELDS.size)
        beginObject()
        while (hasNext()) {
            val field = nextName()
            requireKnownUniqueField(field, EMOTION_FIELDS, fields)
            when (field) {
                "slot" -> slot = readInt("atlases[$atlasIndex].emotions[$entryIndex].slot")
                "id" -> id = readString("atlases[$atlasIndex].emotions[$entryIndex].id")
                "glyph" -> glyph = readString("atlases[$atlasIndex].emotions[$entryIndex].glyph")
            }
        }
        endObject()
        requireCompleteFields(fields, EMOTION_FIELDS, "atlases[$atlasIndex].emotions[$entryIndex]")
        return ParsedEmotion(requireNotNull(slot), requireNotNull(id), requireNotNull(glyph))
    }

    private fun createSnapshot(
        schemaVersion: Int,
        source: BuiltInEmotionSource,
        categories: List<BuiltInEmotionCategory>,
        defaultFavoriteIds: List<EmotionId>,
        atlases: List<ParsedAtlas>,
    ): BuiltInEmotionManifestSnapshot {
        formatRequire(atlases.isNotEmpty()) { "Built-in emotion manifest cannot contain an empty atlas list" }
        formatRequire(atlases.map(ParsedAtlas::texture).distinct().size == atlases.size) {
            "Built-in emotion manifest contains duplicate texture atlases"
        }
        val categoryIds = categories.map(BuiltInEmotionCategory::id).toSet()
        val definitions = ArrayList<BuiltInEmotionDefinition>()
        atlases.forEachIndexed { atlasIndex, atlas ->
            formatRequire(atlas.category in categoryIds) {
                "Emotion atlas $atlasIndex uses unknown category '${atlas.category}'"
            }
            formatRequire(atlas.emotions.isNotEmpty()) { "Emotion atlas $atlasIndex cannot be empty" }
            formatRequire(
                atlas.textureWidth in 1..MAX_TEXTURE_DIMENSION &&
                    atlas.textureHeight in 1..MAX_TEXTURE_DIMENSION &&
                    atlas.spriteWidth in 1..MAX_TEXTURE_DIMENSION &&
                    atlas.spriteHeight in 1..MAX_TEXTURE_DIMENSION,
            ) {
                "Emotion atlas $atlasIndex dimensions must be between 1 and $MAX_TEXTURE_DIMENSION"
            }
            formatRequire(
                atlas.textureWidth % atlas.spriteWidth == 0 && atlas.textureHeight % atlas.spriteHeight == 0,
            ) {
                "Emotion atlas $atlasIndex must contain a complete sprite grid"
            }
            val columns = atlas.textureWidth / atlas.spriteWidth
            val rows = atlas.textureHeight / atlas.spriteHeight
            val capacity = columns.toLong() * rows
            formatRequire(atlas.emotions.map(ParsedEmotion::slot).distinct().size == atlas.emotions.size) {
                "Emotion atlas $atlasIndex contains duplicate slots"
            }
            atlas.emotions.forEach { emotion ->
                formatRequire(emotion.slot.toLong() < capacity) {
                    "Emotion atlas $atlasIndex slot ${emotion.slot} is outside its texture"
                }
                formatRequire(definitions.size < EmotionCatalog.MAX_SIZE) {
                    "Built-in emotion manifest contains too many definitions"
                }
                val id = EmotionId.of(emotion.id)
                definitions.add(
                    BuiltInEmotionDefinition(
                        id = id,
                        texture = atlas.texture,
                        translationKey = id.translationKey(),
                        category = atlas.category,
                        glyph = emotion.glyph,
                        sourceSlot = emotion.slot,
                        region = EmotionSpriteRegion(
                            x = emotion.slot % columns * atlas.spriteWidth,
                            y = emotion.slot / columns * atlas.spriteHeight,
                            width = atlas.spriteWidth,
                            height = atlas.spriteHeight,
                            textureWidth = atlas.textureWidth,
                            textureHeight = atlas.textureHeight,
                        ),
                    ),
                )
            }
        }
        val categoryRank = categories.mapIndexed { index, category -> category.id to index }.toMap()
        val orderedDefinitions = definitions.sortedWith(
            compareBy(
                { definition -> categoryRank.getValue(definition.category) },
                { definition -> definition.sourceSlot },
                { definition -> definition.id.value },
            ),
        )
        return BuiltInEmotionManifestSnapshot(
            schemaVersion,
            source,
            categories,
            defaultFavoriteIds,
            orderedDefinitions,
        )
    }

    private fun JsonReader.requireKnownUniqueField(
        name: String,
        expected: Set<String>,
        seen: MutableSet<String>,
    ) {
        formatRequire(name in expected) { "Unknown manifest field '$name' at $path" }
        formatRequire(seen.add(name)) { "Duplicate manifest field '$name' at $path" }
    }

    private fun JsonReader.readString(fieldPath: String): String {
        expect(JsonToken.STRING, "Manifest field '$fieldPath' must be a string")
        val value = nextString()
        formatRequire(value.length <= MAX_STRING_LENGTH) {
            "Manifest field '$fieldPath' exceeds $MAX_STRING_LENGTH characters"
        }
        return value
    }

    private fun JsonReader.readInt(fieldPath: String): Int {
        expect(JsonToken.NUMBER, "Manifest field '$fieldPath' must be an integer")
        val encoded = nextString()
        formatRequire(INTEGER_PATTERN.matches(encoded)) { "Manifest field '$fieldPath' must be an integer" }
        return encoded.toIntOrNull()
            ?: throw BuiltInEmotionManifestFormatException("Manifest field '$fieldPath' is outside the integer range")
    }

    private fun JsonReader.expect(expected: JsonToken, message: String) {
        formatRequire(peek() == expected) { "$message at $path" }
    }

    private fun requireCompleteFields(seen: Set<String>, expected: Set<String>, path: String) {
        val missing = expected - seen
        formatRequire(missing.isEmpty()) { "Manifest object '$path' is missing fields: ${missing.sorted().joinToString()}" }
    }

    private fun EmotionId.translationKey(): String {
        val separator = value.indexOf(':')
        return "emotion.${value.substring(0, separator)}.${value.substring(separator + 1)}"
    }

    private data class ParsedAtlas(
        val category: String,
        val texture: String,
        val textureWidth: Int,
        val textureHeight: Int,
        val spriteWidth: Int,
        val spriteHeight: Int,
        val emotions: List<ParsedEmotion>,
    )

    private data class ParsedEmotion(
        val slot: Int,
        val id: String,
        val glyph: String,
    )
}

private inline fun formatRequire(condition: Boolean, message: () -> String) {
    if (!condition) {
        throw BuiltInEmotionManifestFormatException(message())
    }
}
