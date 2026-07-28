package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import java.util.IdentityHashMap
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.fml.ModList
import net.neoforged.neoforge.client.settings.IKeyConflictContext

object EmotionPickerMovement {
    private val invMoveInstalled = ModList.get().isLoaded("invmove")
    private val togglePhysicalState = IdentityHashMap<KeyMapping, Boolean>()
    private val contextStates = IdentityHashMap<KeyMapping, MovementContextState>()
    private var controlling = false

    fun update(minecraft: Minecraft) {
        val picker = minecraft.screen as? EmotionPickerScreen
        if (
            invMoveInstalled ||
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
        updateHeld(options.keyUp, window)
        updateHeld(options.keyDown, window)
        updateHeld(options.keyLeft, window)
        updateHeld(options.keyRight, window)
        updateHeld(options.keyJump, window)
        updateToggle(options.keyShift, window)
        updateToggle(options.keySprint, window)
    }

    fun release(minecraft: Minecraft, restorePhysicalState: Boolean = false) {
        if (invMoveInstalled) {
            return
        }
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
        val window = minecraft.window.window
        options.keyUp.setDown(restorePhysicalState && isPhysicallyDown(options.keyUp, window))
        options.keyDown.setDown(restorePhysicalState && isPhysicallyDown(options.keyDown, window))
        options.keyLeft.setDown(restorePhysicalState && isPhysicallyDown(options.keyLeft, window))
        options.keyRight.setDown(restorePhysicalState && isPhysicallyDown(options.keyRight, window))
        options.keyJump.setDown(restorePhysicalState && isPhysicallyDown(options.keyJump, window))
        if (!options.toggleCrouch().get()) {
            options.keyShift.setDown(restorePhysicalState && isPhysicallyDown(options.keyShift, window))
        }
        if (!options.toggleSprint().get()) {
            options.keySprint.setDown(restorePhysicalState && isPhysicallyDown(options.keySprint, window))
        }
    }

    fun isMovementKey(minecraft: Minecraft?, keyCode: Int, scanCode: Int): Boolean {
        val options = minecraft?.options ?: return false
        val input = InputConstants.getKey(keyCode, scanCode)
        return input == options.keyUp.key ||
            input == options.keyDown.key ||
            input == options.keyLeft.key ||
            input == options.keyRight.key ||
            input == options.keyJump.key ||
            input == options.keyShift.key ||
            input == options.keySprint.key
    }

    private fun updateHeld(binding: KeyMapping, window: Long) {
        binding.setDown(isPhysicallyDown(binding, window))
    }

    private fun updateToggle(binding: KeyMapping, window: Long) {
        val down = isPhysicallyDown(binding, window)
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

    private fun isPhysicallyDown(binding: KeyMapping, window: Long): Boolean {
        val key = binding.key
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> false
            InputConstants.Type.SCANCODE -> false
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
