package me.whish.emotify.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class CooldownGate(
    duration: Duration,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val durationNanos = duration.inWholeNanoseconds
    private var lastObservedNanos = timeSource.nowNanos()
    private var readyAtNanos = 0L
    private var acquired = false

    init {
        require(duration.isFinite() && duration.isPositive()) { "Cooldown duration must be finite and positive" }
    }

    fun tryAcquire(): Boolean {
        val nowNanos = observedNow()
        if (acquired && readyAtNanos - nowNanos > 0L) {
            return false
        }

        acquired = true
        readyAtNanos = nowNanos + durationNanos
        return true
    }

    fun remaining(): Duration {
        if (!acquired) {
            return Duration.ZERO
        }

        val remainingNanos = readyAtNanos - observedNow()
        return if (remainingNanos > 0L) remainingNanos.nanoseconds else Duration.ZERO
    }

    private fun observedNow(): Long {
        val nowNanos = timeSource.nowNanos()
        check(nowNanos - lastObservedNanos >= 0L) { "Monotonic time source moved backwards" }
        lastObservedNanos = nowNanos
        return nowNanos
    }
}
