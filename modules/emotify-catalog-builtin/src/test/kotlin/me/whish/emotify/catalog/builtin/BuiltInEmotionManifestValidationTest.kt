package me.whish.emotify.catalog.builtin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class BuiltInEmotionManifestValidationTest : FunSpec({
    test("snapshot rejects partially overlapping regions") {
        val exception = shouldThrow<IllegalArgumentException> {
            snapshot(
                definition("emotify:first", "😀", EmotionSpriteRegion(0, 0, 8, 8, 16, 16)),
                definition("emotify:second", "😢", EmotionSpriteRegion(4, 4, 8, 8, 16, 16)),
            )
        }

        exception.message shouldContain "overlapping"
    }

    test("snapshot rejects conflicting texture dimensions") {
        val exception = shouldThrow<IllegalArgumentException> {
            snapshot(
                definition("emotify:first", "😀", EmotionSpriteRegion(0, 0, 8, 8, 16, 16)),
                definition("emotify:second", "😢", EmotionSpriteRegion(8, 0, 8, 8, 32, 16)),
            )
        }

        exception.message shouldContain "conflicting dimensions"
    }
}) {
    companion object {
        private fun snapshot(vararg definitions: BuiltInEmotionDefinition): BuiltInEmotionManifestSnapshot =
            BuiltInEmotionManifestSnapshot(
                schemaVersion = BuiltInEmotionManifest.SCHEMA_VERSION,
                source = BuiltInEmotionSource("Pack", "Author", "https://example.com/pack", "CC-BY-4.0"),
                categories = listOf(BuiltInEmotionCategory("faces", "category.emotify.faces")),
                defaultFavoriteIds = emptyList(),
                definitions = definitions.asList(),
            )

        private fun definition(
            id: String,
            glyph: String,
            region: EmotionSpriteRegion,
        ): BuiltInEmotionDefinition = BuiltInEmotionDefinition(
            id = EmotionId.of(id),
            texture = "emotify:textures/emotions/faces.png",
            translationKey = "emotion.emotify.${id.substringAfter(':')}",
            category = "faces",
            glyph = glyph,
            sourceSlot = region.x,
            region = region,
        )
    }
}
