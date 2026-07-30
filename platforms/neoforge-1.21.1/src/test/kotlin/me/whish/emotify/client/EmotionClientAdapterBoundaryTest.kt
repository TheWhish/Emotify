package me.whish.emotify.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionClientAdapterBoundaryTest : FunSpec({
    test("unknown shared texture identifiers fail with context") {
        val textureId = "emotify:textures/emotions/missing.png"

        shouldThrow<IllegalArgumentException> {
            EmotionTextureResources.resolve(textureId)
        }.message shouldBe "Unknown emotion texture: $textureId"
    }

    test("edge fade keeps the picker outline color") {
        EmotionPickerTheme.edgeFade shouldBe EmotionPickerTheme.outline
    }
})
