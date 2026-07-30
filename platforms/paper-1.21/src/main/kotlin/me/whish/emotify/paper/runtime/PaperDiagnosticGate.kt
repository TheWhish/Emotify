package me.whish.emotify.paper.runtime

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

class PaperDiagnosticGate(
    burstCapacity: Int,
    refillTokensPerSecond: Int,
    timeSource: MonotonicTimeSource,
) {
    private val monitor = Any()
    private val bucket = TokenBucket(burstCapacity, refillTokensPerSecond, timeSource)

    fun tryAdmit(): Boolean = synchronized(monitor) {
        bucket.tryConsume()
    }
}
