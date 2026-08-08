package me.whish.emotify.server.core

import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

class SelectionIngressGuard(
    timeSource: MonotonicTimeSource,
) {
    private val mainThreadTaskPending = AtomicBoolean()
    private val bucket = TokenBucket(
        capacity = BURST_CAPACITY,
        refillTokensPerSecond = REFILL_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )

    fun tryAdmit(): Boolean = bucket.tryConsume()

    fun shouldForward(emotionId: EmotionId, catalog: EmotionCatalog): Boolean =
        tryAdmit() && catalog.contains(emotionId)

    fun tryReserveMainThreadTask(emotionId: EmotionId, catalog: EmotionCatalog): Boolean =
        shouldForward(emotionId, catalog) && mainThreadTaskPending.compareAndSet(false, true)

    fun tryReserveMainThreadTask(): Boolean =
        tryAdmit() && mainThreadTaskPending.compareAndSet(false, true)

    fun releaseMainThreadTask() {
        check(mainThreadTaskPending.compareAndSet(true, false)) {
            "No Emotify selection main-thread task is pending"
        }
    }

    companion object {
        const val BURST_CAPACITY = 3
        const val REFILL_TOKENS_PER_SECOND = 2
    }
}
