package me.whish.emotify.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent

object EmotionPickerResourceReload {
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::onRegisterReloadListeners)
    }

    private fun onRegisterReloadListeners(event: AddClientReloadListenersEvent) {
        event.addListener(
            Identifier.fromNamespaceAndPath("emotify", "picker_resources"),
            ResourceManagerReloadListener {
                (Minecraft.getInstance().gui.screen() as? EmotionPickerScreen)?.refreshResources()
            },
        )
    }
}
