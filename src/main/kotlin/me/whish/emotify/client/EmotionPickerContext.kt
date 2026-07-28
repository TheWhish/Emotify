package me.whish.emotify.client

import me.whish.emotify.domain.EmotionCatalog

data class EmotionPickerContext(
    val connectionId: Long,
    val allowedEmotions: EmotionCatalog,
) {
    init {
        require(connectionId > 0L) { "Emotion picker connection ID must be positive" }
    }
}
