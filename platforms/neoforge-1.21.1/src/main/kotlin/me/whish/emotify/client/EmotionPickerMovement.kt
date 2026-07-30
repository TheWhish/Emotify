package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import java.util.IdentityHashMap
import me.whish.emotify.client.input.PickerMovementAction
import me.whish.emotify.client.input.PickerMovementInputState
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.neoforged.neoforge.client.settings.IKeyConflictContext
import org.lwjgl.glfw.GLFW

object EmotionPickerMovement {
    private val togglePhysicalState = IdentityHashMap<KeyMapping, Boolean>()
    private val contextStates = IdentityHashMap<KeyMapping, MovementContextState>()
    private val inputState = PickerMovementInputState()
    private var controlling = false

    fun begin(minecraft: Minecraft) {
        inputState.clear()
        val options = minecraft.options
        captureScanCodeState(options.keyUp, PickerMovementAction.FORWARD)
        captureScanCodeState(options.keyDown, PickerMovementAction.BACKWARD)
        captureScanCodeState(options.keyLeft, PickerMovementAction.LEFT)
        captureScanCodeState(options.keyRight, PickerMovementAction.RIGHT)
        captureScanCodeState(options.keyJump, PickerMovementAction.JUMP)
        captureScanCodeState(options.keyShift, PickerMovementAction.CROUCH)
        captureScanCodeState(options.keySprint, PickerMovementAction.SPRINT)
    }

    fun update(minecraft: Minecraft) {
        val picker = minecraft.screen as? EmotionPickerScreen
        if (
            picker == null ||
            !picker.allowsMovementInput() ||
            minecraft.overlay != null ||
            minecraft.player == null
        ) {
            release(minecraft)
            return
        }
        controlling = true
        val window = minecraft.window.window
        val options = minecraft.options
        installContext(options.keyUp)
        installContext(options.keyDown)
        installContext(options.keyLeft)
        installContext(options.keyRight)
        installContext(options.keyJump)
        installContext(options.keyShift)
        installContext(options.keySprint)
        updateHeld(options.keyUp, PickerMovementAction.FORWARD, window)
        updateHeld(options.keyDown, PickerMovementAction.BACKWARD, window)
        updateHeld(options.keyLeft, PickerMovementAction.LEFT, window)
        updateHeld(options.keyRight, PickerMovementAction.RIGHT, window)
        updateHeld(options.keyJump, PickerMovementAction.JUMP, window)
        updateToggle(options.keyShift, PickerMovementAction.CROUCH, window)
        updateToggle(options.keySprint, PickerMovementAction.SPRINT, window)
    }

    fun release(minecraft: Minecraft, restorePhysicalState: Boolean = false) {
        if (!controlling && !restorePhysicalState && !inputState.hasScanCodeInput) {
            return
        }
        controlling = false
        val options = minecraft.options
        try {
            contextStates.forEach { (binding, state) ->
                if (binding.keyConflictContext === state.installed) {
                    binding.keyConflictContext = state.original
                }
            }
            contextStates.clear()
            togglePhysicalState.clear()
            val window = minecraft.window.window
            options.keyUp.setDown(
                restorePhysicalState && isPhysicallyDown(options.keyUp, PickerMovementAction.FORWARD, window),
            )
            options.keyDown.setDown(
                restorePhysicalState && isPhysicallyDown(options.keyDown, PickerMovementAction.BACKWARD, window),
            )
            options.keyLeft.setDown(
                restorePhysicalState && isPhysicallyDown(options.keyLeft, PickerMovementAction.LEFT, window),
            )
            options.keyRight.setDown(
                restorePhysicalState && isPhysicallyDown(options.keyRight, PickerMovementAction.RIGHT, window),
            )
            options.keyJump.setDown(
                restorePhysicalState && isPhysicallyDown(options.keyJump, PickerMovementAction.JUMP, window),
            )
            if (!options.toggleCrouch().get()) {
                options.keyShift.setDown(
                    restorePhysicalState && isPhysicallyDown(options.keyShift, PickerMovementAction.CROUCH, window),
                )
            }
            if (!options.toggleSprint().get()) {
                options.keySprint.setDown(
                    restorePhysicalState && isPhysicallyDown(options.keySprint, PickerMovementAction.SPRINT, window),
                )
            }
        } finally {
            inputState.clear()
        }
    }

    fun keyPressed(minecraft: Minecraft?, keyCode: Int, scanCode: Int): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, keyCode, scanCode, true)
    }

    fun keyReleased(minecraft: Minecraft?, keyCode: Int, scanCode: Int): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, keyCode, scanCode, false)
    }

    fun isMovementMouse(minecraft: Minecraft?, button: Int): Boolean {
        val options = minecraft?.options ?: return false
        return options.keyUp.matchesMouse(button) ||
            options.keyDown.matchesMouse(button) ||
            options.keyLeft.matchesMouse(button) ||
            options.keyRight.matchesMouse(button) ||
            options.keyJump.matchesMouse(button) ||
            options.keyShift.matchesMouse(button) ||
            options.keySprint.matchesMouse(button)
    }

    private fun updateKeyboardState(
        options: Options,
        keyCode: Int,
        scanCode: Int,
        down: Boolean,
    ): Boolean {
        var matched = false
        matched = trackKeyboardBinding(
            options.keyUp,
            PickerMovementAction.FORWARD,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keyDown,
            PickerMovementAction.BACKWARD,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keyLeft,
            PickerMovementAction.LEFT,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keyRight,
            PickerMovementAction.RIGHT,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keyJump,
            PickerMovementAction.JUMP,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keyShift,
            PickerMovementAction.CROUCH,
            keyCode,
            scanCode,
            down,
        ) or matched
        matched = trackKeyboardBinding(
            options.keySprint,
            PickerMovementAction.SPRINT,
            keyCode,
            scanCode,
            down,
        ) or matched
        return matched
    }

    private fun trackKeyboardBinding(
        binding: KeyMapping,
        action: PickerMovementAction,
        keyCode: Int,
        scanCode: Int,
        down: Boolean,
    ): Boolean {
        if (!binding.matches(keyCode, scanCode)) {
            return false
        }
        if (binding.key.type == InputConstants.Type.SCANCODE) {
            inputState.setScanCodeDown(action, down)
        }
        return true
    }

    private fun captureScanCodeState(binding: KeyMapping, action: PickerMovementAction) {
        if (binding.key.type == InputConstants.Type.SCANCODE) {
            inputState.setScanCodeDown(action, binding.isDown)
        }
    }

    private fun updateHeld(binding: KeyMapping, action: PickerMovementAction, window: Long) {
        binding.setDown(isPhysicallyDown(binding, action, window))
    }

    private fun updateToggle(binding: KeyMapping, action: PickerMovementAction, window: Long) {
        val down = isPhysicallyDown(binding, action, window)
        val previous = togglePhysicalState.put(binding, down) ?: false
        if (down != previous) {
            binding.setDown(down)
        }
    }

    private fun installContext(binding: KeyMapping) {
        if (binding in contextStates) {
            return
        }
        val original = binding.keyConflictContext
        val installed = PickerMovementContext(original)
        contextStates[binding] = MovementContextState(original, installed)
        binding.keyConflictContext = installed
    }

    private fun isPhysicallyDown(binding: KeyMapping, action: PickerMovementAction, window: Long): Boolean {
        val key = binding.key
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window, key.value) == GLFW.GLFW_PRESS
            InputConstants.Type.SCANCODE -> inputState.isScanCodeDown(action)
        }
    }

    private class PickerMovementContext(
        private val delegate: IKeyConflictContext,
    ) : IKeyConflictContext {
        override fun isActive(): Boolean =
            delegate.isActive ||
                (Minecraft.getInstance().screen as? EmotionPickerScreen)?.allowsMovementInput() == true

        override fun conflicts(other: IKeyConflictContext): Boolean =
            delegate.conflicts((other as? PickerMovementContext)?.delegate ?: other)
    }

    private data class MovementContextState(
        val original: IKeyConflictContext,
        val installed: PickerMovementContext,
    )
}
