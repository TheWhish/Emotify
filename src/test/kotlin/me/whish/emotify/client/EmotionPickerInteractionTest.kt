package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmotionPickerInteractionTest : FunSpec({
    test("label truncation fills available space without tiny word fragments") {
        val source = "Улыбка с потом"

        EmotionLabelTruncation.completePrefix(source, "Улыбка с п") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с по") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с пот") shouldBe "Улыбка с пот"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с ") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix("Воздушный поцелуй", "Воздушный") shouldBe "Воздушный"
        EmotionLabelTruncation.completePrefix("Смешок", "См") shouldBe "См"
    }

    test("picker toggle closes only on a fresh matching press") {
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = false,
            textInputFocused = false,
        ) shouldBe true
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = true,
            textInputFocused = false,
        ) shouldBe false
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = false,
            bindingDown = false,
            textInputFocused = false,
        ) shouldBe false
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = false,
            textInputFocused = true,
        ) shouldBe false
    }
})
