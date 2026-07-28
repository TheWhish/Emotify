package me.whish.emotify.client

import me.whish.emotify.Emotify
import me.whish.emotify.network.ClientPayloadReceiver
import me.whish.emotify.network.EmotionPlayReceiver
import me.whish.emotify.network.SelectionRejectedReceiver
import me.whish.emotify.network.ServerHelloReceiver
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.common.Mod

@Mod(value = Emotify.ID, dist = [Dist.CLIENT])
class EmotifyClient(modEventBus: IEventBus, modContainer: ModContainer) {
    init {
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientFavoritesConfig.spec, "${Emotify.ID}-client.toml")
        ClientPayloadReceiver.install(
            ServerHelloReceiver(ClientHandshakeController::receive),
            SelectionRejectedReceiver(ClientHandshakeController::receive),
            EmotionPlayReceiver(ClientHandshakeController::receive),
        )
        ClientHandshakeController.register()
        EmotionBillboardRenderer.register()
        EmotionPickerController.register(modEventBus)
        EmotionPickerResourceReload.register(modEventBus)
    }
}
