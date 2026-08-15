package me.whish.emotify.fabric

import me.whish.emotify.fabric.network.FabricNetwork
import me.whish.emotify.fabric.server.FabricServerLifecycle
import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class EmotifyFabric : ModInitializer {
    override fun onInitialize() {
        FabricNetwork.register()
        FabricServerLifecycle.register()
    }

    companion object {
        const val ID = "emotify"
        val LOGGER: Logger = LoggerFactory.getLogger(ID)
    }
}
