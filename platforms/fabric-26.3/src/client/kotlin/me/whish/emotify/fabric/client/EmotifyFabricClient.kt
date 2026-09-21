package me.whish.emotify.fabric.client

import me.whish.emotify.client.ClientHandshakeController
import me.whish.emotify.client.CustomEmojiRegistry
import me.whish.emotify.client.EmotifyClientConfig
import me.whish.emotify.client.EmotionHotbarFeedbackRenderer
import me.whish.emotify.client.EmotionPickerController
import me.whish.emotify.client.EmotionPickerResourceReload
import me.whish.emotify.client.EmotionBillboardRenderTypes
import me.whish.emotify.fabric.EmotifyFabric
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

class EmotifyFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        RenderPipelines.register(EmotionBillboardRenderTypes.pipeline())
        EmotifyClientConfig.initialize()
        ClientLifecycleEvents.CLIENT_STOPPING.register { EmotifyClientConfig.flush() }
        ClientHandshakeController.register()
        EmotionPickerController.register()
        EmotionPickerResourceReload.register()
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.HOTBAR,
            Identifier.fromNamespaceAndPath(EmotifyFabric.ID, "hotbar_feedback"),
        ) { graphics, _ ->
            EmotionHotbarFeedbackRenderer.render(graphics)
        }
        CustomEmojiRegistry.reload(Minecraft.getInstance())
    }
}