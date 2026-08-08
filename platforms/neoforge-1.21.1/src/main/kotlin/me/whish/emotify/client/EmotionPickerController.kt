package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import me.whish.emotify.client.picker.EmotionPickerAccessDecision
import me.whish.emotify.client.picker.EmotionPickerAccessPolicy
import me.whish.emotify.client.picker.EmotionPickerModel
import me.whish.emotify.client.picker.EmotionPickerOpenRequests
import me.whish.emotify.client.picker.EmotionPickerToggleGuard
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW

object EmotionPickerController {
    private val openPickerKey = KeyMapping(
        "key.emotify.open_picker",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        "key.categories.emotify",
    )
    private val openPickerRequests = EmotionPickerOpenRequests(openPickerKey::consumeClick)

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::onRegisterKeyMappings)
        NeoForge.EVENT_BUS.addListener(::onClientTickPre)
        NeoForge.EVENT_BUS.addListener(::onRenderFrame)
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openPickerKey)
    }

    internal fun matchesPickerKey(keyCode: Int, scanCode: Int): Boolean =
        openPickerKey.matches(keyCode, scanCode)

    @Suppress("unused")
    internal fun matchesPickerMouse(button: Int): Boolean = openPickerKey.matchesMouse(button)

    @Suppress("unused")
    internal fun shouldClosePicker(
        keyCode: Int,
        scanCode: Int,
        textInputFocused: Boolean,
    ): Boolean =
        EmotionPickerToggleGuard.shouldClose(
            matchesPickerKey(keyCode, scanCode),
            openPickerKey.isDown,
            textInputFocused,
        )

    @Suppress("UNUSED_PARAMETER")
    private fun onClientTickPre(event: ClientTickEvent.Pre) {
        EmotionPickerMovement.update(Minecraft.getInstance())
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRenderFrame(event: RenderFrameEvent.Post) {
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
            screenOpen = minecraft.screen != null,
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
                minecraft.player?.displayClientMessage(Component.translatable("message.emotify.unavailable"), true)
                return
            }
            EmotionPickerAccessDecision.EMPTY_CATALOG -> {
                minecraft.player?.displayClientMessage(Component.translatable("message.emotify.no_emotions"), true)
                return
            }
            EmotionPickerAccessDecision.OPEN -> Unit
        }

        minecraft.setScreen(EmotionPickerScreen(checkNotNull(context)))
    }
}
