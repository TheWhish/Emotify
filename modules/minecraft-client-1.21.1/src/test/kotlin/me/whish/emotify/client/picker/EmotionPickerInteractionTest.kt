package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.input.PickerMovementAction
import me.whish.emotify.client.input.PickerMovementInputState

@Suppress("unused")
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

    test("picker mouse binding has priority over widgets and movement bindings") {
        EmotionPickerMouseRouting.click(
            matchesPicker = true,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.CLOSE
    }

    test("movement mouse binding cannot activate widgets while movement is enabled") {
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.CONSUME_MOVEMENT
        EmotionPickerMouseRouting.consumeRelease(
            movementAllowed = true,
            matchesMovement = true,
        ) shouldBe true
    }

    test("movement mouse binding can focus and use search input") {
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = true,
        ) shouldBe EmotionPickerMouseDecision.DISPATCH
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = false,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.DISPATCH
        EmotionPickerMouseRouting.consumeRelease(
            movementAllowed = false,
            matchesMovement = true,
        ) shouldBe false
    }

    test("movement key state can be tracked without consuming search input") {
        val inputState = PickerMovementInputState()
        inputState.setScanCodeDown(PickerMovementAction.FORWARD, true)

        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = false,
            matchesMovement = inputState.isScanCodeDown(PickerMovementAction.FORWARD),
        ) shouldBe false
        inputState.isScanCodeDown(PickerMovementAction.FORWARD) shouldBe true
        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = true,
            matchesMovement = inputState.isScanCodeDown(PickerMovementAction.FORWARD),
        ) shouldBe true
        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = true,
            matchesMovement = false,
        ) shouldBe false
    }
})
