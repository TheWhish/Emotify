package me.whish.emotify.client

internal enum class EmotionPickerAccessDecision {
    OPEN,
    SCREEN_OCCUPIED,
    GAME_CONTEXT_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    EMPTY_CATALOG,
}

internal object EmotionPickerAccessPolicy {
    fun decide(
        screenOpen: Boolean,
        worldAvailable: Boolean,
        connectionAvailable: Boolean,
        playerAvailable: Boolean,
        serverContextAvailable: Boolean,
        catalogAvailable: Boolean,
    ): EmotionPickerAccessDecision = when {
        screenOpen -> EmotionPickerAccessDecision.SCREEN_OCCUPIED
        !worldAvailable || !connectionAvailable || !playerAvailable ->
            EmotionPickerAccessDecision.GAME_CONTEXT_UNAVAILABLE
        !serverContextAvailable -> EmotionPickerAccessDecision.SERVER_UNAVAILABLE
        !catalogAvailable -> EmotionPickerAccessDecision.EMPTY_CATALOG
        else -> EmotionPickerAccessDecision.OPEN
    }
}

internal class EmotionPickerOpenRequests(
    private val consumeClick: () -> Boolean,
) {
    fun drain(): Boolean {
        var requested = false
        while (consumeClick()) {
            requested = true
        }
        return requested
    }
}

internal object ClientSelectionEligibility {
    fun canPublish(alive: Boolean, spectator: Boolean, invisible: Boolean): Boolean =
        alive && !spectator && !invisible
}
