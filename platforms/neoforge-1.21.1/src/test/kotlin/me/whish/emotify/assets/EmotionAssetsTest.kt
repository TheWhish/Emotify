package me.whish.emotify.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.InputStreamReader
import java.security.MessageDigest
import javax.imageio.ImageIO
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.catalog.builtin.BuiltInEmotionSource
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.EmotionTextureResources
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.domain.EmotionId

class EmotionAssetsTest : FunSpec({
    test("manifest is the immutable source for the complete built in catalog") {
        val manifest = resourceJson("/assets/emotify/emotions.json")
        manifest["schemaVersion"].asInt shouldBe BuiltInEmotionManifest.SCHEMA_VERSION
        val sourceIds = manifestEntries(manifest).map { entry -> entry["id"].asString }

        sourceIds.size shouldBe 162
        sourceIds shouldContainExactlyInAnyOrder BuiltInEmotionManifest.definitions.map { definition ->
            definition.id.value
        }
        BuiltInEmotionManifest.definitions.map { definition -> definition.id.value } shouldContainExactly
            BuiltInEmotionCatalog.catalog.ids.map(EmotionId::value)
        BuiltInEmotionManifest.find(EmotionId.of("emotify:happy"))?.category shouldBe "faces"
        BuiltInEmotionManifest.find(EmotionId.of("other:unknown")) shouldBe null
        BuiltInEmotionManifest.findCategory("animals")?.translationKey shouldBe "category.emotify.animals"
        BuiltInEmotionManifest.findCategory("unknown") shouldBe null
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.definitions as MutableList).clear()
        }
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.categories as MutableList).clear()
        }
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.defaultFavoriteIds as MutableList).clear()
        }
    }

    test("defaults remain independent from canonical catalog order while both atlases are indexed") {
        BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value) shouldContainExactly listOf(
            "emotify:happy",
            "emotify:sad",
            "emotify:angry",
            "emotify:surprised",
            "emotify:love",
            "emotify:confused",
        )
        BuiltInEmotionManifest.definitions.take(6).map { definition -> definition.id.value } shouldContainExactly listOf(
            "emotify:grinning_face",
            "emotify:beaming_face",
            "emotify:tears_of_joy",
            "emotify:rolling_laugh",
            "emotify:smiling_face",
            "emotify:happy",
        )
        BuiltInEmotionManifest.categories.map { category -> category.id to category.translationKey } shouldContainExactly
            listOf(
                "faces" to "category.emotify.faces",
                "animals" to "category.emotify.animals",
            )
        val byCategory = BuiltInEmotionManifest.definitions.groupBy { definition -> definition.category }
        byCategory.getValue("faces").size shouldBe 130
        byCategory.getValue("animals").size shouldBe 32
        byCategory.getValue("faces").map { definition -> definition.sourceSlot }.sorted() shouldContainExactly
            (0 until 130).toList()
        byCategory.getValue("animals").map { definition -> definition.sourceSlot }.sorted() shouldContainExactly
            (0 until 32).toList()
        BuiltInEmotionManifest.definitions.map { definition -> definition.glyph }.toSet().size shouldBe 162
    }

    test("definitions use category slot and id as a deterministic canonical order") {
        val categoryRank = BuiltInEmotionManifest.categories.mapIndexed { index, category ->
            category.id to index
        }.toMap()
        val expected = BuiltInEmotionManifest.definitions.sortedWith(
            compareBy(
                { definition -> categoryRank.getValue(definition.category) },
                { definition -> definition.sourceSlot },
                { definition -> definition.id.value },
            ),
        )

        BuiltInEmotionManifest.definitions shouldContainExactly expected
    }

    test("manifest attributes both Happy Better Emojis atlases and preserves stable regions") {
        val source = resourceJson("/assets/emotify/emotions.json").getAsJsonObject("source")
        source["name"].asString shouldBe "Happy's Better Emojis"
        source["author"].asString shouldBe "Happy_AlexRO"
        source["url"].asString shouldBe "https://modrinth.com/resourcepack/happys-emojis"
        source["license"].asString shouldBe "CC-BY-4.0"
        BuiltInEmotionManifest.source shouldBe BuiltInEmotionSource(
            source["name"].asString,
            source["author"].asString,
            source["url"].asString,
            source["license"].asString,
        )

        BuiltInEmotionManifest.defaultFavoriteIds.associateWith { emotionId ->
            BuiltInEmotionManifest.find(emotionId)?.region
        } shouldBe linkedMapOf(
            EmotionId.of("emotify:happy") to EmotionSpriteRegion(40, 0, 8, 8, 128, 72),
            EmotionId.of("emotify:sad") to EmotionSpriteRegion(112, 24, 8, 8, 128, 72),
            EmotionId.of("emotify:angry") to EmotionSpriteRegion(0, 40, 8, 8, 128, 72),
            EmotionId.of("emotify:surprised") to EmotionSpriteRegion(24, 16, 8, 8, 128, 72),
            EmotionId.of("emotify:love") to EmotionSpriteRegion(96, 0, 8, 8, 128, 72),
            EmotionId.of("emotify:confused") to EmotionSpriteRegion(16, 24, 8, 8, 128, 72),
        )
        resourceSha256("/assets/emotify/textures/emotions/faces.png") shouldBe
            "6D94951A5340544D8B2CE1E0AED0AAAE1DD789D46C8D76613FE7CBC01C8DD066"
        resourceSha256("/assets/emotify/textures/emotions/animals.png") shouldBe
            "BD34F3F6F8FD30FCC540ABAE989951462660826DDBA6E8740F77AAD36F7626FE"
    }

    test("every atlas region is visible and stays inside its declared texture") {
        BuiltInEmotionManifest.definitions.groupBy { definition -> definition.texture }.forEach { (texture, definitions) ->
            val image = resourceImage(resourcePath(texture))
            definitions.forEach { definition ->
                val region = definition.region
                image.width shouldBe region.textureWidth
                image.height shouldBe region.textureHeight
                (region.x + region.width <= image.width) shouldBe true
                (region.y + region.height <= image.height) shouldBe true
                (region.u0 in 0.0f..1.0f) shouldBe true
                (region.v0 in 0.0f..1.0f) shouldBe true
                (region.u1 in 0.0f..1.0f) shouldBe true
                (region.v1 in 0.0f..1.0f) shouldBe true
                regionContainsVisiblePixel(image, region) shouldBe true
            }
        }
    }

    test("manifest metadata matches the compiled client presentation catalog") {
        val definitions = BuiltInEmotionManifest.definitions
        val presentations = EmotionPresentationCatalog.ordered

        presentations.size shouldBe 162
        presentations.map { presentation -> presentation.emotionId } shouldBe BuiltInEmotionCatalog.catalog.ids
        presentations.indices.forEach { index ->
            val definition = definitions[index]
            val presentation = presentations[index]
            presentation.emotionId shouldBe definition.id
            presentation.textureId shouldBe definition.texture
            EmotionTextureResources.resolve(presentation.textureId).toString() shouldBe definition.texture
            presentation.translationKey shouldBe definition.translationKey
            presentation.category shouldBe definition.category
            presentation.glyph shouldBe definition.glyph
            presentation.sourceSlot shouldBe definition.sourceSlot
            presentation.region shouldBe definition.region
        }
        EmotionPresentationCatalog.categories.map { category -> category.id to category.translationKey } shouldContainExactly
            BuiltInEmotionManifest.categories.map { category -> category.id to category.translationKey }
        EmotionPresentationCatalog.find(EmotionId.of("other:unknown")) shouldBe null
        EmotionPresentationCatalog.findCategory("faces")?.translationKey shouldBe "category.emotify.faces"
        EmotionPresentationCatalog.findCategory("unknown") shouldBe null
    }

    test("texture transparency cannot paint a hidden rectangular background") {
        BuiltInEmotionManifest.definitions.map { definition -> definition.texture }.distinct().forEach { texture ->
            val image = resourceImage(resourcePath(texture))
            var transparentPixels = 0
            var opaquePixels = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val alpha = image.getRGB(x, y) ushr 24
                    (alpha < CUTOUT_ALPHA_THRESHOLD || alpha == MAX_ALPHA) shouldBe true
                    if (alpha < CUTOUT_ALPHA_THRESHOLD) {
                        transparentPixels++
                    } else {
                        opaquePixels++
                    }
                }
            }
            (transparentPixels > 0) shouldBe true
            (opaquePixels > 0) shouldBe true
        }
    }

    test("english and russian translations cover every emotion and category") {
        val interfaceKeys = listOf(
            "screen.emotify.emotion_picker",
            "screen.emotify.scroll_hint",
            "screen.emotify.favorite_hint",
            "screen.emotify.no_favorites",
            "screen.emotify.search_hint",
            "screen.emotify.search_footer",
            "screen.emotify.no_search_results",
            "screen.emotify.add_favorite",
            "screen.emotify.remove_favorite",
            "key.emotify.open_picker",
            "key.categories.emotify",
            "category.emotify.favorites",
            "category.emotify.search",
            "message.emotify.unavailable",
            "message.emotify.no_emotions",
            "message.emotify.selection_cooldown",
            "message.emotify.selection_unavailable",
            "message.emotify.player_state",
            "message.emotify.server_busy",
            "message.emotify.selection_failed",
            "message.emotify.request_pending",
            "message.emotify.request_throttled",
            "message.emotify.emotion_active",
        )
        val expectedKeys = BuiltInEmotionManifest.definitions.map { definition -> definition.translationKey } +
            BuiltInEmotionManifest.categories.map { category -> category.translationKey } +
            interfaceKeys
        val english = resourceJson("/assets/emotify/lang/en_us.json")
        val russian = resourceJson("/assets/emotify/lang/ru_ru.json")

        expectedKeys.size shouldBe 187
        english.keySet() shouldContainExactlyInAnyOrder expectedKeys
        russian.keySet() shouldContainExactlyInAnyOrder expectedKeys
        english.entrySet().all { entry -> entry.value.asString.isNotBlank() } shouldBe true
        russian.entrySet().all { entry -> entry.value.asString.isNotBlank() } shouldBe true
    }

    test("only runtime catalog metadata and attributed assets ship as resources") {
        resourceExists("/assets/emotify/textures/emotions/default.json") shouldBe false
        resourceExists("/META-INF/licenses/emotify/Happy-Better-Emojis-NOTICE.txt") shouldBe true
        resourceExists("/META-INF/licenses/emotify/Happy-Better-Emojis-LICENSE.txt") shouldBe true
    }
}) {
    companion object {
        private const val CUTOUT_ALPHA_THRESHOLD = 26
        private const val MAX_ALPHA = 255

        private fun resourceJson(path: String): JsonObject {
            val stream = checkNotNull(EmotionAssetsTest::class.java.getResourceAsStream(path)) {
                "Missing test resource: $path"
            }
            return stream.use { JsonParser.parseReader(InputStreamReader(it, Charsets.UTF_8)).asJsonObject }
        }

        private fun resourceImage(path: String): BufferedImage {
            val stream = checkNotNull(EmotionAssetsTest::class.java.getResourceAsStream(path)) {
                "Missing texture resource: $path"
            }
            return stream.use(ImageIO::read)
        }

        private fun resourceExists(path: String): Boolean =
            EmotionAssetsTest::class.java.getResource(path) != null

        private fun resourceSha256(path: String): String {
            val stream = checkNotNull(EmotionAssetsTest::class.java.getResourceAsStream(path)) {
                "Missing test resource: $path"
            }
            val digest = stream.use { MessageDigest.getInstance("SHA-256").digest(it.readAllBytes()) }
            return digest.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }

        private fun resourcePath(location: String): String {
            val separator = location.indexOf(':')
            require(separator in 1 until location.lastIndex) { "Invalid resource location: $location" }
            val namespace = location.substring(0, separator)
            val path = location.substring(separator + 1)
            require(".." !in path) { "Unsafe resource path: $location" }
            return "/assets/$namespace/$path"
        }

        private fun regionContainsVisiblePixel(image: BufferedImage, region: EmotionSpriteRegion): Boolean {
            for (y in region.y until region.y + region.height) {
                for (x in region.x until region.x + region.width) {
                    if (image.getRGB(x, y) ushr 24 >= CUTOUT_ALPHA_THRESHOLD) {
                        return true
                    }
                }
            }
            return false
        }

        private fun manifestEntries(manifest: JsonObject): List<JsonObject> =
            manifest.getAsJsonArray("atlases").flatMap { atlas ->
                atlas.asJsonObject.getAsJsonArray("emotions").map { entry -> entry.asJsonObject }
            }
    }
}
