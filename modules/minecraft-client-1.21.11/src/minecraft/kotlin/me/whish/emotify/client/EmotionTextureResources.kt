package me.whish.emotify.client

import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.resources.Identifier

object EmotionTextureResources {
    private val byTextureId: Map<String, Identifier> = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered
            .map { presentation -> presentation.textureId }
            .distinct()
            .associateWith(Identifier::parse),
    )

    fun resolve(textureId: String): Identifier =
        requireNotNull(
            byTextureId[textureId]
                ?: CustomEmojiRegistry.resolveTexture(textureId)
                ?: RemoteCustomEmojiRegistry.resolveTexture(textureId),
        ) {
            "Unknown emotion texture: $textureId"
        }
}
