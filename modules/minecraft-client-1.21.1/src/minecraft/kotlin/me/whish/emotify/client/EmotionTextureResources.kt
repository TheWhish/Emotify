package me.whish.emotify.client

import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.resources.ResourceLocation

object EmotionTextureResources {
    private val byTextureId: Map<String, ResourceLocation> = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered
            .map { presentation -> presentation.textureId }
            .distinct()
            .associateWith(ResourceLocation::parse),
    )

    fun resolve(textureId: String): ResourceLocation =
        requireNotNull(byTextureId[textureId]) { "Unknown emotion texture: $textureId" }
}
