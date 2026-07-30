package me.whish.emotify.client.input

enum class PickerMovementAction {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    JUMP,
    CROUCH,
    SPRINT,
}

class PickerMovementInputState {
    private var scanCodeMask = 0

    val hasScanCodeInput: Boolean
        get() = scanCodeMask != 0

    fun setScanCodeDown(action: PickerMovementAction, down: Boolean) {
        val bit = 1 shl action.ordinal
        scanCodeMask = if (down) scanCodeMask or bit else scanCodeMask and bit.inv()
    }

    fun isScanCodeDown(action: PickerMovementAction): Boolean =
        scanCodeMask and (1 shl action.ordinal) != 0

    fun clear() {
        scanCodeMask = 0
    }
}
