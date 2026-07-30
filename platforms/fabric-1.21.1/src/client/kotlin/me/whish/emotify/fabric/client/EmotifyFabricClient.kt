package me.whish.emotify.fabric.client

import me.whish.emotify.client.ClientHandshakeController
import me.whish.emotify.client.EmotifyClientConfig
import me.whish.emotify.client.EmotionPickerController
import me.whish.emotify.client.EmotionPickerResourceReload
import net.fabricmc.api.ClientModInitializer

class EmotifyFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        EmotifyClientConfig.initialize()
        ClientHandshakeController.register()
        EmotionPickerController.register()
        EmotionPickerResourceReload.register()
    }
}
