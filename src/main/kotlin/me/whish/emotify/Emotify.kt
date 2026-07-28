package me.whish.emotify

import me.whish.emotify.network.EmotifyNetwork
import me.whish.emotify.server.ServerHandshakeLifecycle
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Mod(Emotify.ID)
class Emotify(modEventBus: IEventBus) {
    init {
        EmotifyNetwork.register(modEventBus)
        ServerHandshakeLifecycle.register()
    }

    companion object {
        const val ID = "emotify"
        val LOGGER: Logger = LoggerFactory.getLogger(ID)
    }
}
