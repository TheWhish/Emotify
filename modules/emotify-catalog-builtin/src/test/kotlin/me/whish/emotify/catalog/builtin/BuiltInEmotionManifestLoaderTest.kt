package me.whish.emotify.catalog.builtin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class BuiltInEmotionManifestLoaderTest : FunSpec({
    test("loader builds a deterministic category slot and id order") {
        val snapshot = load(validManifest())

        snapshot.definitions.map { definition -> definition.id.value } shouldContainExactly listOf(
            "emotify:happy",
            "emotify:sad",
            "emotify:cat",
        )
        snapshot.defaultFavoriteIds.map(EmotionId::value) shouldContainExactly listOf("emotify:sad", "emotify:happy")
    }

    test("loader uses emotion ID as the final canonical tie breaker") {
        val categories = "[{\"id\":\"faces\",\"translationKey\":\"category.emotify.faces\"}]"
        val first = load(validManifest(categories, "[]", tiedAtlases(false)))
        val second = load(validManifest(categories, "[]", tiedAtlases(true)))
        val expected = listOf("emotify:alpha", "emotify:zed")

        first.definitions.map { definition -> definition.id.value } shouldContainExactly expected
        second.definitions.map { definition -> definition.id.value } shouldContainExactly expected
    }

    test("loader rejects duplicate and unknown JSON fields") {
        val duplicate = validManifest().replaceFirst(
            "\"schemaVersion\": 4,",
            "\"schemaVersion\": 4, \"schemaVersion\": 4,",
        )
        val unknown = validManifest().replaceFirst(
            "\"schemaVersion\": 4,",
            "\"schemaVersion\": 4, \"unexpected\": true,",
        )

        shouldThrow<BuiltInEmotionManifestFormatException> { load(duplicate) }.message shouldContain "Duplicate"
        shouldThrow<BuiltInEmotionManifestFormatException> { load(unknown) }.message shouldContain "Unknown"
    }

    test("loader rejects trailing data malformed integers and invalid UTF8") {
        val trailing = "${validManifest()} {}"
        val overflow = validManifest().replaceFirst("\"textureWidth\": 16", "\"textureWidth\": 999999999999")
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)

        shouldThrow<BuiltInEmotionManifestFormatException> { load(trailing) }
        shouldThrow<BuiltInEmotionManifestFormatException> { load(overflow) }.message shouldContain "integer range"
        shouldThrow<BuiltInEmotionManifestFormatException> {
            BuiltInEmotionManifestLoader.load(ByteArrayInputStream(invalidUtf8))
        }
    }

    test("loader rejects comments wrong field types and noncanonical numbers") {
        val commented = validManifest().replaceFirst(
            "\"schemaVersion\": 4,",
            "/*invalid*/ \"schemaVersion\": 4,",
        )
        val wrongType = validManifest().replaceFirst("\"schemaVersion\": 4", "\"schemaVersion\": \"4\"")
        val decimal = validManifest().replaceFirst("\"textureWidth\": 16", "\"textureWidth\": 16.0")

        listOf(commented, wrongType, decimal).forEach { manifest ->
            shouldThrow<BuiltInEmotionManifestFormatException> { load(manifest) }
        }
    }

    test("loader rejects documents above the hard byte limit before parsing") {
        val oversized = ByteArray(BuiltInEmotionManifestLoader.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }

        shouldThrow<BuiltInEmotionManifestFormatException> {
            BuiltInEmotionManifestLoader.load(ByteArrayInputStream(oversized))
        }.message shouldContain "exceeds"
    }

    test("loader rejects duplicate texture atlases empty atlases and unused categories") {
        val duplicateTexture = validManifest().replace(
            "emotify:textures/emotions/animals.png",
            "emotify:textures/emotions/faces.png",
        )
        val emptyAtlas = validManifest().replace(
            "\"emotions\": [{\"slot\": 0, \"id\": \"emotify:cat\", \"glyph\": \"🐱\"}]",
            "\"emotions\": []",
        )
        val unusedCategory = validManifest().replace(
            "{\"id\": \"animals\", \"translationKey\": \"category.emotify.animals\"}",
            "{\"id\": \"creatures\", \"translationKey\": \"category.emotify.creatures\"}",
        ).replace("\"category\": \"animals\"", "\"category\": \"faces\"")

        shouldThrow<BuiltInEmotionManifestFormatException> { load(duplicateTexture) }
        shouldThrow<BuiltInEmotionManifestFormatException> { load(emptyAtlas) }
        shouldThrow<BuiltInEmotionManifestFormatException> { load(unusedCategory) }
    }

    test("loader caps the total definition count") {
        val manifest = validManifest(
            categories = "[{\"id\":\"faces\",\"translationKey\":\"category.emotify.faces\"}]",
            defaultFavorites = "[]",
            atlases = """
                [
                  {
                    "category":"faces",
                    "texture":"emotify:textures/emotions/faces.png",
                    "textureWidth":256,
                    "textureHeight":1,
                    "spriteWidth":1,
                    "spriteHeight":1,
                    "emotions":[${definitions(0, 256)}]
                  },
                  {
                    "category":"faces",
                    "texture":"emotify:textures/emotions/animals.png",
                    "textureWidth":512,
                    "textureHeight":1,
                    "spriteWidth":1,
                    "spriteHeight":1,
                    "emotions":[${definitions(256, 257)}]
                  }
                ]
            """.trimIndent(),
        )

        shouldThrow<BuiltInEmotionManifestFormatException> { load(manifest) }
    }
}) {
    companion object {
        private fun load(manifest: String): BuiltInEmotionManifestSnapshot =
            BuiltInEmotionManifestLoader.load(manifest.byteInputStream(Charsets.UTF_8))

        private fun definitions(start: Int, count: Int): String = (start until start + count).joinToString(",") { index ->
            val glyph = String(Character.toChars(0x1F300 + index))
            val slot = index - start
            "{\"slot\":$slot,\"id\":\"emotify:e$index\",\"glyph\":\"$glyph\"}"
        }

        private fun tiedAtlases(reversed: Boolean): String {
            val zed = """
                {
                  "category":"faces",
                  "texture":"emotify:textures/emotions/faces.png",
                  "textureWidth":8,
                  "textureHeight":8,
                  "spriteWidth":8,
                  "spriteHeight":8,
                  "emotions":[{"slot":0,"id":"emotify:zed","glyph":"😀"}]
                }
            """.trimIndent()
            val alpha = """
                {
                  "category":"faces",
                  "texture":"emotify:textures/emotions/animals.png",
                  "textureWidth":8,
                  "textureHeight":8,
                  "spriteWidth":8,
                  "spriteHeight":8,
                  "emotions":[{"slot":0,"id":"emotify:alpha","glyph":"😢"}]
                }
            """.trimIndent()
            return if (reversed) "[$alpha,$zed]" else "[$zed,$alpha]"
        }

        private fun validManifest(
            categories: String = """
                [
                  {"id": "faces", "translationKey": "category.emotify.faces"},
                  {"id": "animals", "translationKey": "category.emotify.animals"}
                ]
            """.trimIndent(),
            defaultFavorites: String = "[\"emotify:sad\", \"emotify:happy\"]",
            atlases: String = """
                [
                  {
                    "category": "animals",
                    "texture": "emotify:textures/emotions/animals.png",
                    "textureWidth": 8,
                    "textureHeight": 8,
                    "spriteWidth": 8,
                    "spriteHeight": 8,
                    "emotions": [{"slot": 0, "id": "emotify:cat", "glyph": "🐱"}]
                  },
                  {
                    "category": "faces",
                    "texture": "emotify:textures/emotions/faces.png",
                    "textureWidth": 16,
                    "textureHeight": 8,
                    "spriteWidth": 8,
                    "spriteHeight": 8,
                    "emotions": [
                      {"slot": 1, "id": "emotify:sad", "glyph": "😢"},
                      {"slot": 0, "id": "emotify:happy", "glyph": "😀"}
                    ]
                  }
                ]
            """.trimIndent(),
        ): String = """
            {
              "schemaVersion": 4,
              "source": {
                "name": "Pack",
                "author": "Author",
                "url": "https://example.com/pack",
                "license": "CC-BY-4.0"
              },
              "categories": $categories,
              "defaultFavorites": $defaultFavorites,
              "atlases": $atlases
            }
        """.trimIndent()
    }
}
