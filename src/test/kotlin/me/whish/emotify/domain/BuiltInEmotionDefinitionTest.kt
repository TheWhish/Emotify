package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

class BuiltInEmotionDefinitionTest : FunSpec({
    test("built in emotion rejects a foreign id namespace") {
        shouldThrow<IllegalArgumentException> {
            definition(id = "external:happy")
        }
    }

    test("built in emotion rejects a foreign texture namespace") {
        shouldThrow<IllegalArgumentException> {
            definition(texture = "external:textures/emotions/faces.png")
        }
    }

    test("built in emotion rejects an unsafe texture path") {
        shouldThrow<IllegalArgumentException> {
            definition(texture = "emotify:textures/emotions/../faces.png")
        }
    }

    test("built in emotion rejects malformed unicode and bidi controls") {
        listOf("\uD83D", " ", "\u202E").forEach { glyph ->
            shouldThrow<IllegalArgumentException> {
                definition(glyph = glyph)
            }
        }
    }
}) {
    companion object {
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
