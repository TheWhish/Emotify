package me.whish.emotify.protocol

import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.MAX_SELECTION_RETRY_AFTER_MILLIS
import me.whish.emotify.domain.SelectionRejectionReason

data class EmotionSelection(
    val emotionId: EmotionId,
)

@JvmInline
value class SelectionRejectionCode(
    val value: Int,
) {
    init {
        require(value in 0..255) { "Selection rejection code must fit U8: $value" }
    }

    val knownReason: SelectionRejectionReason?
        get() = when (value) {
            0 -> SelectionRejectionReason.COOLDOWN
            1 -> SelectionRejectionReason.SERVER_DISABLED
            2 -> SelectionRejectionReason.EMOTION_DISABLED
            3 -> SelectionRejectionReason.PLAYER_STATE
            4 -> SelectionRejectionReason.SERVER_BUSY
            else -> null
        }

    companion object {
        fun from(reason: SelectionRejectionReason): SelectionRejectionCode = when (reason) {
            SelectionRejectionReason.COOLDOWN -> SelectionRejectionCode(0)
            SelectionRejectionReason.SERVER_DISABLED -> SelectionRejectionCode(1)
            SelectionRejectionReason.EMOTION_DISABLED -> SelectionRejectionCode(2)
            SelectionRejectionReason.PLAYER_STATE -> SelectionRejectionCode(3)
            SelectionRejectionReason.SERVER_BUSY -> SelectionRejectionCode(4)
        }
    }
}

data class SelectionRejected(
    val code: SelectionRejectionCode,
    val retryAfterMillis: Int,
) {
    init {
        require(retryAfterMillis in 0..MAX_SELECTION_RETRY_AFTER_MILLIS) {
            "Retry delay must be between 0 and $MAX_SELECTION_RETRY_AFTER_MILLIS ms: $retryAfterMillis"
        }
    }
}
