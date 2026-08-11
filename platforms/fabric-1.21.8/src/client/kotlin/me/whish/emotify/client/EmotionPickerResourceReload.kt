package me.whish.emotify.client

import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

object EmotionPickerResourceReload {
    fun register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = RELOAD_LISTENER_ID

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    (Minecraft.getInstance().screen as? EmotionPickerScreen)?.refreshResources()
                }
            },
        )
    }

    private val RELOAD_LISTENER_ID = ResourceLocation.fromNamespaceAndPath("emotify", "picker")
}
