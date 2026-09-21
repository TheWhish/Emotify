package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import java.util.IdentityHashMap
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.neoforged.neoforge.client.settings.IKeyConflictContext

@Suppress("unused")
object EmotionPickerMovement {
    private val togglePhysicalState = IdentityHashMap<KeyMapping, Boolean>()
    private val contextStates = IdentityHashMap<KeyMapping, MovementContextState>()
    private var controlling = false

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
        val options = minecraft.options
        installContext(options.keyUp)
        installContext(options.keyDown)
        installContext(options.keyLeft)
        installContext(options.keyRight)
        installContext(options.keyJump)
        installContext(options.keyShift)
        installContext(options.keySprint)
        updateHeld(options.keyUp, minecraft)
        updateHeld(options.keyDown, minecraft)
        updateHeld(options.keyLeft, minecraft)
        updateHeld(options.keyRight, minecraft)
        updateHeld(options.keyJump, minecraft)
        updateToggle(options.keyShift, minecraft)
        updateToggle(options.keySprint, minecraft)
    }

    fun release(minecraft: Minecraft, restorePhysicalState: Boolean = false) {
        if (!controlling && !restorePhysicalState) {
            return
        }
        controlling = false
        val options = minecraft.options
        contextStates.forEach { (binding, state) ->
            if (binding.keyConflictContext === state.installed) {
                binding.keyConflictContext = state.original
            }
        }
        contextStates.clear()
        togglePhysicalState.clear()
        options.keyUp.setDown(restorePhysicalState && isPhysicallyDown(options.keyUp, minecraft))
        options.keyDown.setDown(restorePhysicalState && isPhysicallyDown(options.keyDown, minecraft))
        options.keyLeft.setDown(restorePhysicalState && isPhysicallyDown(options.keyLeft, minecraft))
        options.keyRight.setDown(restorePhysicalState && isPhysicallyDown(options.keyRight, minecraft))
        options.keyJump.setDown(restorePhysicalState && isPhysicallyDown(options.keyJump, minecraft))
        if (!options.toggleCrouch().get()) {
            options.keyShift.setDown(restorePhysicalState && isPhysicallyDown(options.keyShift, minecraft))
        }
        if (!options.toggleSprint().get()) {
            options.keySprint.setDown(restorePhysicalState && isPhysicallyDown(options.keySprint, minecraft))
        }
    }

    fun keyPressed(minecraft: Minecraft?, event: KeyEvent): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, event)
    }

    fun keyReleased(minecraft: Minecraft?, event: KeyEvent): Boolean {
        val options = minecraft?.options ?: return false
        return updateKeyboardState(options, event)
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

    private fun updateKeyboardState(options: Options, event: KeyEvent): Boolean =
        options.keyUp.matches(event) ||
            options.keyDown.matches(event) ||
            options.keyLeft.matches(event) ||
            options.keyRight.matches(event) ||
            options.keyJump.matches(event) ||
            options.keyShift.matches(event) ||
            options.keySprint.matches(event)

    private fun updateHeld(binding: KeyMapping, minecraft: Minecraft) {
        binding.setDown(isPhysicallyDown(binding, minecraft))
    }

    private fun updateToggle(binding: KeyMapping, minecraft: Minecraft) {
        val down = isPhysicallyDown(binding, minecraft)
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

    private fun isPhysicallyDown(binding: KeyMapping, minecraft: Minecraft): Boolean {
        val key = binding.key
        return when (key.type) {
            InputConstants.Type.KEYBOARD -> InputConstants.isKeyDown(key.value)
            InputConstants.Type.MOUSE -> isMouseButtonPhysicallyDown(minecraft, key.value, binding)
        }
    }

    private fun isMouseButtonPhysicallyDown(minecraft: Minecraft, value: Int, binding: KeyMapping): Boolean =
        when (value) {
            InputConstants.MOUSE_BUTTON_LEFT -> minecraft.mouseHandler.isLeftPressed
            InputConstants.MOUSE_BUTTON_RIGHT -> minecraft.mouseHandler.isRightPressed
            InputConstants.MOUSE_BUTTON_MIDDLE -> minecraft.mouseHandler.isMiddlePressed
            else -> binding.isDown
        }

    private class PickerMovementContext(
        private val delegate: IKeyConflictContext,
    ) : IKeyConflictContext {
        override fun isActive(): Boolean =
            delegate.isActive ||
                (Minecraft.getInstance().gui.screen() as? EmotionPickerScreen)?.allowsMovementInput() == true

        override fun conflicts(other: IKeyConflictContext): Boolean =
            delegate.conflicts((other as? PickerMovementContext)?.delegate ?: other)
    }

    private data class MovementContextState(
        val original: IKeyConflictContext,
        val installed: PickerMovementContext,
    )
}
