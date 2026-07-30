package me.whish.emotify.server.core

import me.whish.emotify.domain.EmotionCatalog

data class ServerSelectionPolicy(
    val enabled: Boolean,
    val catalog: EmotionCatalog,
    val allowedEmotions: EmotionCatalog,
) {
    init {
        require(allowedEmotions.ids.all(catalog::contains)) {
            "Allowed emotions must be a subset of the server catalog"
        }
    }
}
