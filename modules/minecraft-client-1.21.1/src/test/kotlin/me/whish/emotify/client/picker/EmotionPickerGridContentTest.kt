package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.custom.CustomEmojiDiagnostic
import me.whish.emotify.client.custom.CustomEmojiDiagnosticReason
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class EmotionPickerGridContentTest : FunSpec({
    test("every diagnostic reason resolves to one detailed tooltip message") {
        val keys = CustomEmojiDiagnosticReason.entries.map(CustomEmojiDiagnosticReason::translationKey)

        keys.distinct().size shouldBe CustomEmojiDiagnosticReason.entries.size
        keys.all { key -> key.endsWith(".detail") } shouldBe true
    }

    val available = EmotionPresentation(
        EmotionId.of("emotify_custom:0123456789abcdef"),
        "emotify_custom:0123456789abcdef",
        "",
        EmotionPickerModel.CUSTOM_SECTION_ID,
        "",
        0,
        EmotionSpriteRegion(0, 0, 8, 8, 8, 8),
        "Working",
    )

    test("custom grid always places available emotions before stable diagnostic cards") {
        val diagnostics = listOf(
            CustomEmojiDiagnostic("zeta", CustomEmojiFileFormat.GIF, CustomEmojiDiagnosticReason.TOO_MANY_FRAMES),
            CustomEmojiDiagnostic("Alpha", CustomEmojiFileFormat.PNG, CustomEmojiDiagnosticReason.INVALID_IMAGE),
            CustomEmojiDiagnostic("alpha", CustomEmojiFileFormat.JPEG, CustomEmojiDiagnosticReason.FILE_TOO_LARGE),
        )

        val content = EmotionPickerGridContent.custom(listOf(available), diagnostics)

        content.filterIsInstance<EmotionPickerGridItem.Available>()
            .map { item -> item.presentation }
            .shouldContainExactly(available)
        content.filterIsInstance<EmotionPickerGridItem.UnavailableCustom>()
            .map { item -> item.diagnostic.displayName }
            .shouldContainExactly("Alpha", "alpha", "zeta")
        content.first() shouldBe EmotionPickerGridItem.Available(available)
    }

    test("regular grid cannot contain unavailable custom files") {
        val content = EmotionPickerGridContent.regular(listOf(available))

        content.shouldContainExactly(EmotionPickerGridItem.Available(available))
        content.none { item -> item is EmotionPickerGridItem.UnavailableCustom } shouldBe true
    }
})
