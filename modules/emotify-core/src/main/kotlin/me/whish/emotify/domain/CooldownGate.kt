package me.whish.emotify.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class CooldownGate(
    duration: Duration,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private var durationNanos = validatedDurationNanos(duration)
    private var lastObservedNanos = timeSource.nowNanos()
    private var acquiredAtNanos = 0L
    private var acquired = false

    fun tryAcquire(): Boolean {
        val nowNanos = observedNow()
        if (acquired && durationNanos - (nowNanos - acquiredAtNanos) > 0L) {
            return false
        }

        acquired = true
        acquiredAtNanos = nowNanos
        return true
    }

    fun remaining(): Duration {
        if (!acquired) {
            return Duration.ZERO
        }

        val remainingNanos = durationNanos - (observedNow() - acquiredAtNanos)
        return if (remainingNanos > 0L) remainingNanos.nanoseconds else Duration.ZERO
    }

    fun reconfigure(duration: Duration) {
        val replacementNanos = validatedDurationNanos(duration)
        observedNow()
        durationNanos = replacementNanos
    }

    private fun observedNow(): Long {
        val nowNanos = timeSource.nowNanos()
        check(nowNanos - lastObservedNanos >= 0L) { "Monotonic time source moved backwards" }
        lastObservedNanos = nowNanos
        return nowNanos
    }

    private fun validatedDurationNanos(duration: Duration): Long {
        require(duration.isFinite() && duration.isPositive()) { "Cooldown duration must be finite and positive" }
        return duration.inWholeNanoseconds
    }
}
