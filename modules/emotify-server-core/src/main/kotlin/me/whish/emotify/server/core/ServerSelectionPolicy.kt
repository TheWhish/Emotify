package me.whish.emotify.server.core

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels

data class ServerSelectionPolicy(
    val enabled: Boolean,
    val catalog: EmotionCatalog,
    val allowedEmotions: EmotionCatalog,
    val customEmojisEnabled: Boolean = true,
    val maximumStaticCustomEmojiSize: Int = CustomEmojiPixels.MAXIMUM_SIZE,
    val maximumAnimatedCustomEmojiSize: Int = CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE,
) {
    init {
        require(allowedEmotions.ids.all(catalog::contains)) {
            "Allowed emotions must be a subset of the server catalog"
        }
        require(CustomEmojiPixels.supports(maximumStaticCustomEmojiSize)) {
            "Maximum static custom emoji size is unsupported: $maximumStaticCustomEmojiSize"
        }
        require(CustomEmojiPixels.supports(maximumAnimatedCustomEmojiSize)) {
            "Maximum animated custom emoji size is unsupported: $maximumAnimatedCustomEmojiSize"
        }
        require(maximumAnimatedCustomEmojiSize <= CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE) {
            "Maximum animated custom emoji size exceeds the protocol limit: $maximumAnimatedCustomEmojiSize"
        }
        require(maximumAnimatedCustomEmojiSize <= maximumStaticCustomEmojiSize) {
            "Maximum animated custom emoji size cannot exceed the static limit"
        }
    }

    fun allows(asset: CustomEmojiAsset): Boolean = asset.pixels.size <= if (asset.isAnimated) {
        maximumAnimatedCustomEmojiSize
    } else {
        maximumStaticCustomEmojiSize
    }
}
