package me.whish.emotify.server

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

data class PlayerSelectionState(
    val alive: Boolean,
    val spectator: Boolean,
    val invisible: Boolean,
) {
    val canPublish: Boolean
        get() = alive && !spectator && !invisible
}
