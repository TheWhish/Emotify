package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
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

    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::onRegisterKeyMappings)
        NeoForge.EVENT_BUS.addListener(::onClientTickPre)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openPickerKey)
    }

    internal fun matchesPickerKey(keyCode: Int, scanCode: Int): Boolean =
        openPickerKey.matches(keyCode, scanCode)

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

    private fun onClientTickPre(event: ClientTickEvent.Pre) {
        EmotionPickerMovement.update(Minecraft.getInstance())
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        var requested = false
        while (openPickerKey.consumeClick()) {
            requested = true
        }
        if (!requested) {
            return
        }

        val minecraft = Minecraft.getInstance()
        if (minecraft.screen != null || minecraft.level == null || minecraft.connection == null) {
            return
        }
        val player = minecraft.player ?: return
        if (!player.isAlive || player.isSpectator || player.isInvisible) {
            player.displayClientMessage(Component.translatable("message.emotify.player_state"), true)
            return
        }
        val context = ClientHandshakeController.pickerContext()
        if (context == null) {
            player.displayClientMessage(Component.translatable("message.emotify.unavailable"), true)
            return
        }
        val model = EmotionPickerModel.from(context.allowedEmotions)
        if (model.initialState() == null) {
            player.displayClientMessage(Component.translatable("message.emotify.no_emotions"), true)
            return
        }

        minecraft.setScreen(EmotionPickerScreen(context))
        EmotionPickerMovement.update(minecraft)
    }
}
