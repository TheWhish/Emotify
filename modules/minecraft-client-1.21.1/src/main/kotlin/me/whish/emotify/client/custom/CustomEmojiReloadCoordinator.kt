package me.whish.emotify.client.custom

sealed interface CustomEmojiReloadCompletion {
    data object FollowUp : CustomEmojiReloadCompletion
    data class Finished(
        val success: Boolean,
        val callbacks: List<() -> Unit>,
        val resultCallbacks: List<(Boolean) -> Unit>,
    ) : CustomEmojiReloadCompletion
}

class CustomEmojiReloadCoordinator {
    private val monitor = Any()
    private var callback: (() -> Unit)? = null
    private val resultCallbacks = LinkedHashSet<(Boolean) -> Unit>()
    private var inFlight = false
    private var followUpRequested = false

    fun request(onComplete: () -> Unit): Boolean = synchronized(monitor) {
        callback = onComplete
        if (inFlight) {
            followUpRequested = true
            false
        } else {
            inFlight = true
            true
        }
    }

    fun isInFlight(): Boolean = synchronized(monitor) { inFlight }

    fun requestWithResult(onComplete: (Boolean) -> Unit): Boolean = synchronized(monitor) {
        resultCallbacks += onComplete
        if (inFlight) {
            followUpRequested = true
            false
        } else {
            inFlight = true
            true
        }
    }

    fun subscribe(onComplete: () -> Unit): Boolean = synchronized(monitor) {
        if (!inFlight) {
            return@synchronized false
        }
        callback = onComplete
        true
    }

    fun complete(success: Boolean, retry: Boolean = false): CustomEmojiReloadCompletion = synchronized(monitor) {
        check(inFlight) { "A custom emoji reload cannot complete without an active load" }
        if (retry || followUpRequested) {
            followUpRequested = false
            return@synchronized CustomEmojiReloadCompletion.FollowUp
        }
        inFlight = false
        val completedCallbacks = if (success) listOfNotNull(callback) else emptyList()
        val completedResultCallbacks = java.util.List.copyOf(resultCallbacks)
        callback = null
        resultCallbacks.clear()
        CustomEmojiReloadCompletion.Finished(success, completedCallbacks, completedResultCallbacks)
    }
}
