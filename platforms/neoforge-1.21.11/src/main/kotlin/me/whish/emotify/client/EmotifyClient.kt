package me.whish.emotify.client

import me.whish.emotify.Emotify
import me.whish.emotify.network.ClientPayloadReceiver
import me.whish.emotify.network.EmotionPlayReceiver
import me.whish.emotify.network.SelectionRejectedReceiver
import me.whish.emotify.network.ServerHelloReceiver
import me.whish.emotify.network.CustomEmojiAssetReceiver
import me.whish.emotify.network.CustomEmojiAssetChunkReceiver
import me.whish.emotify.network.CustomEmotionPlayReceiver
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.GameShuttingDownEvent
import net.minecraft.client.Minecraft

@Mod(value = Emotify.ID, dist = [Dist.CLIENT])
class EmotifyClient(modEventBus: IEventBus, modContainer: ModContainer) {
    init {
        if (EmotifyClientConfig.prepareForRegistration()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, EmotifyClientConfig.spec, "${Emotify.ID}-client.toml")
        }
        modContainer.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { _, parent -> EmotifySettingsScreen(parent) },
        )
        NeoForge.EVENT_BUS.addListener<GameShuttingDownEvent> { EmotifyClientConfig.flush() }
        ClientPayloadReceiver.install(
            ServerHelloReceiver(ClientHandshakeController::receive),
            SelectionRejectedReceiver(ClientHandshakeController::receive),
            EmotionPlayReceiver(ClientHandshakeController::receive),
            CustomEmojiAssetReceiver(ClientHandshakeController::receive),
            CustomEmojiAssetChunkReceiver(ClientHandshakeController::receive),
            CustomEmotionPlayReceiver(ClientHandshakeController::receive),
        )
        modEventBus.addListener(NeoForgeClientPayloadRegistration::register)
        ClientHandshakeController.register()
        CustomEmojiCopyInput.register()
        EmotionBillboardRenderer.register()
        EmotionPickerController.register(modEventBus)
        EmotionPickerResourceReload.register(modEventBus)
        modEventBus.addListener(::onClientSetup)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            CustomEmojiRegistry.reload(Minecraft.getInstance())
        }
    }
}
