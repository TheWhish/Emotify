package me.whish.emotify.client.input

import me.whish.emotify.client.settings.ClientConfigurationSchema

object QuickSlotKeyResolver {
    const val NO_SLOT = -1
    private const val INPUTS_PER_SOURCE = ClientConfigurationSchema.QUICK_SLOT_COUNT
    private const val INPUT_COUNT = INPUTS_PER_SOURCE * 2

    fun resolve(
        keyCode: Int,
        numberRowFirstKeyCode: Int,
        keypadFirstKeyCode: Int,
    ): Int {
        val numberRowIndex = keyCode - numberRowFirstKeyCode
        if (numberRowIndex in 0 until ClientConfigurationSchema.QUICK_SLOT_COUNT) {
            return numberRowIndex
        }
        val keypadIndex = keyCode - keypadFirstKeyCode
        return if (keypadIndex in 0 until ClientConfigurationSchema.QUICK_SLOT_COUNT) {
            keypadIndex + INPUTS_PER_SOURCE
        } else {
            NO_SLOT
        }
    }

    fun slotIndex(inputIndex: Int): Int {
        require(inputIndex in 0 until INPUT_COUNT) { "Quick slot input index is outside the supported range: $inputIndex" }
        return inputIndex % INPUTS_PER_SOURCE
    }
}

enum class QuickSlotPressDecision {
    ACTIVATE_WITH_CLICK_FEEDBACK,
    CONSUME_REPEAT,
    DISPATCH_TO_TEXT_INPUT,
}

object QuickSlotInputRouting {
    fun press(searchInputFocused: Boolean, edgePress: Boolean): QuickSlotPressDecision = when {
        searchInputFocused -> QuickSlotPressDecision.DISPATCH_TO_TEXT_INPUT
        edgePress -> QuickSlotPressDecision.ACTIVATE_WITH_CLICK_FEEDBACK
        else -> QuickSlotPressDecision.CONSUME_REPEAT
    }
}

class QuickSlotInputGate {
    private var pressedMask = 0

    fun press(inputIndex: Int): Boolean {
        val bit = inputBit(inputIndex)
        if (pressedMask and bit != 0) {
            return false
        }
        val counterpartBit = inputBit(counterpart(inputIndex))
        val counterpartPressed = pressedMask and counterpartBit != 0
        pressedMask = pressedMask or bit
        return !counterpartPressed
    }

    fun release(inputIndex: Int): Boolean {
        val bit = inputBit(inputIndex)
        val wasPressed = pressedMask and bit != 0
        pressedMask = pressedMask and bit.inv()
        return wasPressed
    }

    fun clear() {
        pressedMask = 0
    }

    fun releaseMissing(physicallyPressedMask: Int) {
        require(physicallyPressedMask and SUPPORTED_INPUT_MASK.inv() == 0) {
            "Physical quick slot input mask contains unsupported bits: $physicallyPressedMask"
        }
        pressedMask = pressedMask and physicallyPressedMask
    }

    private fun counterpart(inputIndex: Int): Int =
        if (inputIndex < ClientConfigurationSchema.QUICK_SLOT_COUNT) {
            inputIndex + ClientConfigurationSchema.QUICK_SLOT_COUNT
        } else {
            inputIndex - ClientConfigurationSchema.QUICK_SLOT_COUNT
        }

    private fun inputBit(inputIndex: Int): Int {
        require(inputIndex in 0 until ClientConfigurationSchema.QUICK_SLOT_COUNT * 2) {
            "Quick slot input index is outside the supported range: $inputIndex"
        }
        return 1 shl inputIndex
    }

    private companion object {
        const val INPUT_COUNT = ClientConfigurationSchema.QUICK_SLOT_COUNT * 2
        const val SUPPORTED_INPUT_MASK = (1 shl INPUT_COUNT) - 1
    }
}
