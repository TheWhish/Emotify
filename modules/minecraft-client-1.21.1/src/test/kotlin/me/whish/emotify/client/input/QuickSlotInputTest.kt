package me.whish.emotify.client.input

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class QuickSlotInputTest : FunSpec({
    test("number row and keypad keys resolve to the same nine slots") {
        val numberRowOne = 49
        val keypadOne = 321

        repeat(9) { index ->
            QuickSlotKeyResolver.resolve(numberRowOne + index, numberRowOne, keypadOne) shouldBe index
            QuickSlotKeyResolver.resolve(keypadOne + index, numberRowOne, keypadOne) shouldBe index + 9
            QuickSlotKeyResolver.slotIndex(index) shouldBe index
            QuickSlotKeyResolver.slotIndex(index + 9) shouldBe index
        }
        QuickSlotKeyResolver.resolve(48, numberRowOne, keypadOne) shouldBe QuickSlotKeyResolver.NO_SLOT
        QuickSlotKeyResolver.resolve(58, numberRowOne, keypadOne) shouldBe QuickSlotKeyResolver.NO_SLOT
        QuickSlotKeyResolver.resolve(320, numberRowOne, keypadOne) shouldBe QuickSlotKeyResolver.NO_SLOT
        QuickSlotKeyResolver.resolve(330, numberRowOne, keypadOne) shouldBe QuickSlotKeyResolver.NO_SLOT
    }

    test("input gate accepts only the edge press until release") {
        val gate = QuickSlotInputGate()

        gate.press(4) shouldBe true
        gate.press(4) shouldBe false
        gate.release(4) shouldBe true
        gate.release(4) shouldBe false
        gate.press(4) shouldBe true
    }

    test("input gate tracks slots independently and clears on screen removal") {
        val gate = QuickSlotInputGate()

        gate.press(0) shouldBe true
        gate.press(8) shouldBe true
        gate.press(0) shouldBe false
        gate.press(8) shouldBe false

        gate.clear()

        gate.press(0) shouldBe true
        gate.press(8) shouldBe true
    }

    test("number row and keypad counterparts stay suppressed until both are released") {
        val gate = QuickSlotInputGate()

        gate.press(2) shouldBe true
        gate.press(11) shouldBe false
        gate.release(2) shouldBe true
        gate.press(11) shouldBe false
        gate.release(11) shouldBe true
        gate.press(11) shouldBe true
    }

    test("physical key reconciliation recovers from a lost release event") {
        val gate = QuickSlotInputGate()

        gate.press(0) shouldBe true
        gate.releaseMissing(0)
        gate.press(0) shouldBe true

        gate.press(9) shouldBe false
        gate.releaseMissing(1 shl 9)
        gate.releaseMissing(0)
        gate.press(9) shouldBe true
    }

    test("focused search receives digits while picker actions consume only edge presses") {
        QuickSlotInputRouting.press(searchInputFocused = true, edgePress = true) shouldBe
            QuickSlotPressDecision.DISPATCH_TO_TEXT_INPUT
        QuickSlotInputRouting.press(searchInputFocused = true, edgePress = false) shouldBe
            QuickSlotPressDecision.DISPATCH_TO_TEXT_INPUT
        QuickSlotInputRouting.press(searchInputFocused = false, edgePress = true) shouldBe
            QuickSlotPressDecision.ACTIVATE_WITH_CLICK_FEEDBACK
        QuickSlotInputRouting.press(searchInputFocused = false, edgePress = false) shouldBe
            QuickSlotPressDecision.CONSUME_REPEAT
    }
})
