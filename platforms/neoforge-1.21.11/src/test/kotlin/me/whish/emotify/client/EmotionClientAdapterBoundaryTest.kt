package me.whish.emotify.client

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionClientAdapterBoundaryTest {
    @Test
    fun `unknown shared texture identifiers fail with context`() {
        val textureId = "emotify:textures/emotions/missing.png"

        shouldThrow<IllegalArgumentException> {
            EmotionTextureResources.resolve(textureId)
        }.message shouldBe "Unknown emotion texture: $textureId"
    }

    @Test
    fun `edge fade keeps the picker outline color`() {
        EmotionPickerTheme.edgeFade shouldBe EmotionPickerTheme.outline
    }
}
