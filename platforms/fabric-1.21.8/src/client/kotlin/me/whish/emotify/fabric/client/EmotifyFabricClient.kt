@file:Suppress("DEPRECATION")

package me.whish.emotify.fabric.client

import me.whish.emotify.client.ClientHandshakeController
import me.whish.emotify.client.CustomEmojiRegistry
import me.whish.emotify.client.EmotifyClientConfig
import me.whish.emotify.client.EmotionBillboardRenderer
import me.whish.emotify.client.EmotionHotbarFeedbackRenderer
import me.whish.emotify.client.EmotionPickerController
import me.whish.emotify.client.EmotionPickerResourceReload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.Minecraft

class EmotifyFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        EmotifyClientConfig.initialize()
        ClientLifecycleEvents.CLIENT_STOPPING.register { EmotifyClientConfig.flush() }
        ClientHandshakeController.register()
        EmotionBillboardRenderer.register()
        EmotionPickerController.register()
        EmotionPickerResourceReload.register()
        HudRenderCallback.EVENT.register { guiGraphics, _ -> EmotionHotbarFeedbackRenderer.render(guiGraphics) }
        CustomEmojiRegistry.reload(Minecraft.getInstance())
    }
}