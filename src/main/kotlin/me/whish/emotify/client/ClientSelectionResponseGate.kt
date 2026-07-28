package me.whish.emotify.client

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource

class ClientSelectionResponseGate(
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
    private val pendingTimeoutNanos: Long = DEFAULT_PENDING_TIMEOUT_NANOS,
) {
    private var pending = false
    private var pendingSinceNanos = 0L
    private var lastObservedNanos = 0L
    private var hasObservedTime = false

    init {
        require(pendingTimeoutNanos > 0L) { "Selection response timeout must be positive" }
    }

    fun tryReserve(): Boolean {
        val nowNanos = observeTime()
        expireAt(nowNanos)
        if (pending) {
            return false
        }
        pending = true
        pendingSinceNanos = nowNanos
        return true
    }

    fun cancelReservation() {
        pending = false
    }

    fun tryConsumeRejection(): Boolean = tryConsumePending()

    fun tryConsumeSuccess(): Boolean = tryConsumePending()

    fun reset() {
        pending = false
        hasObservedTime = false
    }

    private fun tryConsumePending(): Boolean {
        val nowNanos = observeTime()
        expireAt(nowNanos)
        if (!pending) {
            return false
        }
        pending = false
        return true
    }

    private fun expireAt(nowNanos: Long) {
        if (pending && nowNanos - pendingSinceNanos >= pendingTimeoutNanos) {
            pending = false
        }
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
