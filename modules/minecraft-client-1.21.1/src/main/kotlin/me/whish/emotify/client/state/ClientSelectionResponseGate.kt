package me.whish.emotify.client.state

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.EmotionId

class ClientSelectionResponseGate(
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
    private val pendingTimeoutNanos: Long = DEFAULT_PENDING_TIMEOUT_NANOS,
) {
    private var pending = false
    private var pendingEmotion: EmotionId? = null
    private var pendingSinceNanos = 0L
    private var lastObservedNanos = 0L
    private var hasObservedTime = false

    init {
        require(pendingTimeoutNanos > 0L) { "Selection response timeout must be positive" }
    }

    fun tryReserve(emotionId: EmotionId): Boolean {
        val nowNanos = observeTime()
        expireAt(nowNanos)
        if (pending) {
            return false
        }
        pending = true
        pendingEmotion = emotionId
        pendingSinceNanos = nowNanos
        return true
    }

    fun cancelReservation() {
        clearPending()
    }

    fun tryConsumeRejection(): Boolean = tryConsumePending()

    fun tryConsumeSuccess(emotionId: EmotionId): Boolean {
        if (pendingEmotion != emotionId) {
            return false
        }
        return tryConsumePending()
    }

    fun reset() {
        clearPending()
        hasObservedTime = false
    }

    private fun tryConsumePending(): Boolean {
        val nowNanos = observeTime()
        expireAt(nowNanos)
        if (!pending) {
            return false
        }
        clearPending()
        return true
    }

    private fun expireAt(nowNanos: Long) {
        if (pending && nowNanos - pendingSinceNanos >= pendingTimeoutNanos) {
            clearPending()
        }
    }

    private fun clearPending() {
        pending = false
        pendingEmotion = null
    }

    private fun observeTime(): Long {
        val nowNanos = timeSource.nowNanos()
        if (hasObservedTime) {
            check(nowNanos - lastObservedNanos >= 0L) { "Monotonic time source moved backwards" }
        }
        lastObservedNanos = nowNanos
        hasObservedTime = true
        return nowNanos
    }

    companion object {
        const val DEFAULT_PENDING_TIMEOUT_NANOS = 10_000_000_000L
    }
}
