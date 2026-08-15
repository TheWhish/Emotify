package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import me.whish.emotify.client.picker.EmotionPickerAccessDecision
import me.whish.emotify.client.picker.EmotionPickerAccessPolicy
import me.whish.emotify.client.picker.EmotionPickerModel
import me.whish.emotify.client.picker.EmotionPickerOpenRequests
import me.whish.emotify.client.picker.EmotionPickerToggleGuard
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

object EmotionPickerController {
    private val pickerCategory = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("emotify", "main"),
    )
    private val openPickerKey = KeyMapping(
        "key.emotify.open_picker",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        pickerCategory,
    )
    private val openPickerRequests = EmotionPickerOpenRequests(openPickerKey::consumeClick)

    fun register() {
        KeyMappingHelper.registerKeyMapping(openPickerKey)
        ClientTickEvents.START_CLIENT_TICK.register(EmotionPickerMovement::update)
    }

    internal fun matchesPickerKey(event: KeyEvent): Boolean = openPickerKey.matches(event)

    internal fun matchesPickerMouse(event: MouseButtonEvent): Boolean = openPickerKey.matchesMouse(event)

    internal fun shouldClosePicker(
        event: KeyEvent,
        textInputFocused: Boolean,
    ): Boolean = EmotionPickerToggleGuard.shouldClose(
        matchesPickerKey(event),
        openPickerKey.isDown,
        textInputFocused,
    )

    @JvmStatic
    fun onRenderFrame() {
        if (!openPickerRequests.drain()) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val context = ClientHandshakeController.pickerContext()
        val model = context?.let { available ->
            EmotionPickerModel.from(
                available.allowedEmotions,
                customEmojis = CustomEmojiRegistry.presentations(),
            )
        }
        val decision = EmotionPickerAccessPolicy.decide(
            screenOpen = minecraft.gui.screen() != null,
            worldAvailable = minecraft.level != null,
            connectionAvailable = minecraft.connection != null,
            playerAvailable = minecraft.player != null,
            serverContextAvailable = context != null,
            catalogAvailable = model?.initialState() != null,
        )
        when (decision) {
            EmotionPickerAccessDecision.SCREEN_OCCUPIED,
            EmotionPickerAccessDecision.GAME_CONTEXT_UNAVAILABLE,
            -> return
            EmotionPickerAccessDecision.SERVER_UNAVAILABLE -> {
                minecraft.player?.sendOverlayMessage(Component.translatable("message.emotify.unavailable"))
                return
            }
            EmotionPickerAccessDecision.EMPTY_CATALOG -> {
                minecraft.player?.sendOverlayMessage(Component.translatable("message.emotify.no_emotions"))
                return
            }
            EmotionPickerAccessDecision.OPEN -> Unit
        }

        minecraft.gui.setScreen(EmotionPickerScreen(checkNotNull(context)))
    }
}
