package me.whish.emotify.client.custom

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CustomEmojiReloadCompletion {
    data object FollowUp : CustomEmojiReloadCompletion
    data class Finished(
        val success: Boolean,
        val callbacks: List<() -> Unit>,
        val resultCallbacks: List<(Boolean) -> Unit>,
    ) : CustomEmojiReloadCompletion
}

class CustomEmojiRefreshScheduler {
    private val inFlight = AtomicBoolean()

    fun <T> submit(
        executor: Executor,
        task: () -> T,
        onComplete: (T?, Throwable?) -> Unit,
    ): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            return false
        }
        try {
            CompletableFuture.supplyAsync({ task() }, executor).whenComplete { result: T?, failure: Throwable? ->
                inFlight.set(false)
                onComplete(result, failure)
            }
        } catch (failure: RuntimeException) {
            inFlight.set(false)
            onComplete(null, failure)
        }
        return true
    }
}

class CustomEmojiReloadCoordinator {
    private val monitor = Any()
    private var callback: (() -> Unit)? = null
    private val resultCallbacks = LinkedHashSet<(Boolean) -> Unit>()
    private var inFlight = false
    private var followUpRequested = false
    private var automaticRetryCount = 0

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
        if (followUpRequested) {
            followUpRequested = false
            return@synchronized CustomEmojiReloadCompletion.FollowUp
        }
        if (retry && automaticRetryCount < MAXIMUM_AUTOMATIC_RETRIES) {
            automaticRetryCount += 1
            return@synchronized CustomEmojiReloadCompletion.FollowUp
        }
        inFlight = false
        automaticRetryCount = 0
        val completedCallbacks = if (success) listOfNotNull(callback) else emptyList()
        val completedResultCallbacks = java.util.List.copyOf(resultCallbacks)
        callback = null
        resultCallbacks.clear()
        CustomEmojiReloadCompletion.Finished(success, completedCallbacks, completedResultCallbacks)
    }

    private companion object {
        const val MAXIMUM_AUTOMATIC_RETRIES = 1
    }
}
