package me.whish.emotify.fabric.client

import me.whish.emotify.client.ClientHandshakeController
import me.whish.emotify.client.CustomEmojiRegistry
import me.whish.emotify.client.EmotifyClientConfig
import me.whish.emotify.client.EmotionPickerController
import me.whish.emotify.client.EmotionPickerResourceReload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.client.Minecraft

class EmotifyFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        EmotifyClientConfig.initialize()
        ClientLifecycleEvents.CLIENT_STOPPING.register { EmotifyClientConfig.flush() }
        ClientHandshakeController.register()
        EmotionPickerController.register()
        EmotionPickerResourceReload.register()
        CustomEmojiRegistry.reload(Minecraft.getInstance())
    }
}
