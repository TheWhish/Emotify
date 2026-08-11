package me.whish.emotify.network

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

internal class NetworkDiagnosticGate(
    capacity: Int = DEFAULT_CAPACITY,
    refillTokensPerSecond: Int = DEFAULT_REFILL_TOKENS_PER_SECOND,
    timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val monitor = Any()
    private val diagnostics = TokenBucket(capacity, refillTokensPerSecond, timeSource)

    fun tryAdmit(): Boolean = synchronized(monitor) {
        diagnostics.tryConsume()
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_REFILL_TOKENS_PER_SECOND = 2
    }
}
