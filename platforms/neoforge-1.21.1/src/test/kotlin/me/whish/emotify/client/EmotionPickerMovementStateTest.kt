package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

@Suppress("unused")
class EmotionPickerMovementStateTest : FunSpec({
    test("scancode actions retain independent pressed state") {
        val state = PickerMovementInputState()

        state.setScanCodeDown(PickerMovementAction.FORWARD, true)
        state.setScanCodeDown(PickerMovementAction.JUMP, true)

        state.isScanCodeDown(PickerMovementAction.FORWARD).shouldBeTrue()
        state.isScanCodeDown(PickerMovementAction.JUMP).shouldBeTrue()
        state.isScanCodeDown(PickerMovementAction.SPRINT).shouldBeFalse()

        state.setScanCodeDown(PickerMovementAction.FORWARD, false)

        state.isScanCodeDown(PickerMovementAction.FORWARD).shouldBeFalse()
        state.isScanCodeDown(PickerMovementAction.JUMP).shouldBeTrue()
    }

    test("lifecycle clear releases every tracked scancode action") {
        val state = PickerMovementInputState()
        PickerMovementAction.entries.forEach { action -> state.setScanCodeDown(action, true) }

        state.hasScanCodeInput.shouldBeTrue()
        state.clear()

        state.hasScanCodeInput.shouldBeFalse()
        PickerMovementAction.entries.forEach { action ->
            state.isScanCodeDown(action).shouldBeFalse()
        }
    }
})
