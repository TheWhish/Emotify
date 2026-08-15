package me.whish.emotify.client

import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener

object EmotionPickerResourceReload {
    fun register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(
            RELOAD_LISTENER_ID,
            ResourceManagerReloadListener {
                (Minecraft.getInstance().screen as? EmotionPickerScreen)?.refreshResources()
            },
        )
    }

    private val RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath("emotify", "picker")
}
