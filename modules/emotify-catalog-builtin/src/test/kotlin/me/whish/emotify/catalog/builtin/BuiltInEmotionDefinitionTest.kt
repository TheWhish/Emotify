package me.whish.emotify.catalog.builtin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class BuiltInEmotionDefinitionTest : FunSpec({
    test("built in emotion rejects a foreign id namespace") {
        shouldThrow<IllegalArgumentException> {
            definition(id = "external:happy")
        }
    }

    test("built in emotion rejects foreign and unsafe texture paths") {
        listOf(
            "external:textures/emotions/faces.png",
            "emotify:textures/emotions/../faces.png",
            "emotify:textures/emotions//faces.png",
            "emotify:textures/emotions/faces.jpg",
        ).forEach { texture ->
            shouldThrow<IllegalArgumentException> {
                definition(texture = texture)
            }
        }
    }

    test("built in emotion rejects malformed unicode controls whitespace and oversized glyphs") {
        listOf("\uD83D", "\uDC00", " ", "\n", "\u061C", "\u202E", "\u2067", "123456789").forEach { glyph ->
            shouldThrow<IllegalArgumentException> {
                definition(glyph = glyph)
            }
        }
    }

    test("built in emotion accepts a composed emoji glyph") {
        definition(glyph = "👩‍💻️").glyph shouldBe "👩‍💻️"
    }

    test("sprite region validates bounds and derives exact UV coordinates") {
        val region = EmotionSpriteRegion(8, 16, 8, 8, 32, 32)

        region.u0.shouldBeExactly(0.25f)
        region.v0.shouldBeExactly(0.5f)
        region.u1.shouldBeExactly(0.5f)
        region.v1.shouldBeExactly(0.75f)
        listOf(
            { EmotionSpriteRegion(0, 0, 1, 1, 0, 8) },
            { EmotionSpriteRegion(0, 0, 9, 8, 8, 8) },
            { EmotionSpriteRegion(-1, 0, 8, 8, 8, 8) },
            { EmotionSpriteRegion(1, 0, 8, 8, 8, 8) },
        ).forEach { invalidRegion ->
            shouldThrow<IllegalArgumentException>(invalidRegion)
        }
    }

    test("source attribution requires safe metadata and an https URL") {
        BuiltInEmotionSource("Pack", "Author", "https://example.com/pack", "CC-BY-4.0").license shouldBe "CC-BY-4.0"
        listOf("http://example.com", "https://user@example.com", "not-a-url").forEach { url ->
            shouldThrow<IllegalArgumentException> {
                BuiltInEmotionSource("Pack", "Author", url, "CC-BY-4.0")
            }
        }
        listOf(
            BuiltInEmotionSourceInput("", "Author", "CC-BY-4.0"),
            BuiltInEmotionSourceInput("Pack", "Author\n", "CC-BY-4.0"),
            BuiltInEmotionSourceInput("Pack\u202E", "Author", "CC-BY-4.0"),
            BuiltInEmotionSourceInput("P".repeat(257), "Author", "CC-BY-4.0"),
            BuiltInEmotionSourceInput("Pack", "Author", ""),
            BuiltInEmotionSourceInput("Pack", "Author", "invalid license"),
            BuiltInEmotionSourceInput("Pack", "Author", "L".repeat(65)),
        ).forEach { input ->
            shouldThrow<IllegalArgumentException> {
                BuiltInEmotionSource(input.name, input.author, "https://example.com/pack", input.license)
            }
        }
    }
}) {
    companion object {
        private data class BuiltInEmotionSourceInput(
            val name: String,
            val author: String,
            val license: String,
        )

        private fun definition(
            id: String = "emotify:happy",
            texture: String = "emotify:textures/emotions/faces.png",
            glyph: String = "😀",
        ): BuiltInEmotionDefinition = BuiltInEmotionDefinition(
            id = EmotionId.of(id),
            texture = texture,
            translationKey = "emotion.emotify.happy",
            category = "faces",
            glyph = glyph,
            sourceSlot = 0,
            region = EmotionSpriteRegion(0, 0, 8, 8, 8, 8),
        )
    }
}
