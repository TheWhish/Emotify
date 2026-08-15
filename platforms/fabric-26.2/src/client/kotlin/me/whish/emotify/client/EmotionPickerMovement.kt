package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.Window
import java.util.IdentityHashMap
import me.whish.emotify.client.input.PickerMovementAction
import me.whish.emotify.client.input.PickerMovementInputState
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

object EmotionPickerMovement {
    private val togglePhysicalState = IdentityHashMap<KeyMapping, Boolean>()
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
        val picker = minecraft.gui.screen() as? EmotionPickerScreen
        if (
            picker == null ||
            !picker.allowsMovementInput() ||
            minecraft.gui.overlay() != null ||
            minecraft.player == null
        ) {
            release(minecraft)
            return
        }
        controlling = true
        val window = minecraft.window
        val options = minecraft.options
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
            togglePhysicalState.clear()
            val window = minecraft.window
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

    fun keyPressed(minecraft: Minecraft?, event: KeyEvent): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, event, true)
    }

    fun keyReleased(minecraft: Minecraft?, event: KeyEvent): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, event, false)
    }

    fun isMovementMouse(minecraft: Minecraft?, event: MouseButtonEvent): Boolean {
        val options = minecraft?.options ?: return false
        return options.keyUp.matchesMouse(event) ||
            options.keyDown.matchesMouse(event) ||
            options.keyLeft.matchesMouse(event) ||
            options.keyRight.matchesMouse(event) ||
            options.keyJump.matchesMouse(event) ||
            options.keyShift.matchesMouse(event) ||
            options.keySprint.matchesMouse(event)
    }

    private fun updateKeyboardState(
        options: Options,
        event: KeyEvent,
        down: Boolean,
    ): Boolean {
        var matched = false
        matched = trackKeyboardBinding(options.keyUp, PickerMovementAction.FORWARD, event, down) or matched
        matched = trackKeyboardBinding(options.keyDown, PickerMovementAction.BACKWARD, event, down) or matched
        matched = trackKeyboardBinding(options.keyLeft, PickerMovementAction.LEFT, event, down) or matched
        matched = trackKeyboardBinding(options.keyRight, PickerMovementAction.RIGHT, event, down) or matched
        matched = trackKeyboardBinding(options.keyJump, PickerMovementAction.JUMP, event, down) or matched
        matched = trackKeyboardBinding(options.keyShift, PickerMovementAction.CROUCH, event, down) or matched
        matched = trackKeyboardBinding(options.keySprint, PickerMovementAction.SPRINT, event, down) or matched
        return matched
    }

    private fun trackKeyboardBinding(
        binding: KeyMapping,
        action: PickerMovementAction,
        event: KeyEvent,
        down: Boolean,
    ): Boolean {
        if (!binding.matches(event)) {
            return false
        }
        if (KeyMappingHelper.getBoundKeyOf(binding).type == InputConstants.Type.SCANCODE) {
            inputState.setScanCodeDown(action, down)
        }
        return true
    }

    private fun captureScanCodeState(binding: KeyMapping, action: PickerMovementAction) {
        if (KeyMappingHelper.getBoundKeyOf(binding).type == InputConstants.Type.SCANCODE) {
            inputState.setScanCodeDown(action, binding.isDown)
        }
    }

    private fun updateHeld(binding: KeyMapping, action: PickerMovementAction, window: Window) {
        binding.setDown(isPhysicallyDown(binding, action, window))
    }

    private fun updateToggle(binding: KeyMapping, action: PickerMovementAction, window: Window) {
        val down = isPhysicallyDown(binding, action, window)
        val previous = togglePhysicalState.put(binding, down) ?: false
        if (down != previous) {
            binding.setDown(down)
        }
    }

    private fun isPhysicallyDown(binding: KeyMapping, action: PickerMovementAction, window: Window): Boolean {
        val key = KeyMappingHelper.getBoundKeyOf(binding)
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window.handle(), key.value) == GLFW.GLFW_PRESS
            InputConstants.Type.SCANCODE -> inputState.isScanCodeDown(action)
        }
    }
}
