package me.whish.emotify.client

import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent

object EmotionPickerResourceReload {
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::onRegisterReloadListeners)
    }

    private fun onRegisterReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(
            ResourceManagerReloadListener {
                (Minecraft.getInstance().screen as? EmotionPickerScreen)?.refreshResources()
            },
        )
    }
}
