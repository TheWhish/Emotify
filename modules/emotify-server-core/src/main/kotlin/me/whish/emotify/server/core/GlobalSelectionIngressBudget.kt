package me.whish.emotify.server.core

import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

sealed interface GlobalSelectionIngressAdmission {
    data class Admitted(val lease: GlobalSelectionIngressLease) : GlobalSelectionIngressAdmission

    data object OutstandingLimitReached : GlobalSelectionIngressAdmission

    data object RateLimited : GlobalSelectionIngressAdmission
}

enum class GlobalSelectionIngressRelease {
    RELEASED,
    STALE_AFTER_RESET,
    ALREADY_RELEASED,
}

data class GlobalSelectionIngressSnapshot(
    val outstanding: Int,
    val availableRequestTokens: Int,
)

data class GlobalSelectionIngressLimits(
    val maximumOutstanding: Int,
    val requestBurstCapacity: Int,
    val requestRefillTokensPerSecond: Int,
) {
    init {
        require(maximumOutstanding > 0) {
            "Maximum outstanding selection count must be positive: $maximumOutstanding"
        }
        require(requestBurstCapacity > 0) { "Selection request burst capacity must be positive: $requestBurstCapacity" }
        require(requestRefillTokensPerSecond > 0) {
            "Selection request refill rate must be positive: $requestRefillTokensPerSecond"
        }
    }
}

class GlobalSelectionIngressLease internal constructor(
    private val owner: GlobalSelectionIngressBudget,
    private val generation: Long,
) {
    private val released = AtomicBoolean()

    fun release(): GlobalSelectionIngressRelease {
        if (!released.compareAndSet(false, true)) {
            return GlobalSelectionIngressRelease.ALREADY_RELEASED
        }
        return owner.release(generation)
    }
}

class GlobalSelectionIngressBudget(
    private var maxOutstanding: Int = 512,
    requestBurstCapacity: Int = 1_024,
    requestRefillTokensPerSecond: Int = 512,
    timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val monitor = Any()
    private val requests = TokenBucket(requestBurstCapacity, requestRefillTokensPerSecond, timeSource)
    private var outstanding = 0
    private var generation = 0L

    init {
        require(maxOutstanding > 0) { "Maximum outstanding selection count must be positive: $maxOutstanding" }
    }

    fun tryAcquire(): GlobalSelectionIngressAdmission = synchronized(monitor) {
        if (outstanding >= maxOutstanding) {
            return@synchronized GlobalSelectionIngressAdmission.OutstandingLimitReached
        }
        if (!requests.tryConsume()) {
            return@synchronized GlobalSelectionIngressAdmission.RateLimited
        }

        outstanding += 1
        GlobalSelectionIngressAdmission.Admitted(GlobalSelectionIngressLease(this, generation))
    }

    fun snapshot(): GlobalSelectionIngressSnapshot = synchronized(monitor) {
        GlobalSelectionIngressSnapshot(outstanding, requests.availableWholeTokens())
    }

    fun reset() {
        synchronized(monitor) {
            outstanding = 0
            requests.reset()
            generation = Math.incrementExact(generation)
        }
    }

    fun reconfigure(limits: GlobalSelectionIngressLimits) {
        synchronized(monitor) {
            maxOutstanding = limits.maximumOutstanding
            requests.reconfigure(limits.requestBurstCapacity, limits.requestRefillTokensPerSecond)
        }
    }

    internal fun release(leaseGeneration: Long): GlobalSelectionIngressRelease = synchronized(monitor) {
        if (leaseGeneration != generation) {
            return@synchronized GlobalSelectionIngressRelease.STALE_AFTER_RESET
        }
        check(outstanding > 0) { "No global Emotify selection task is outstanding" }
        outstanding -= 1
        GlobalSelectionIngressRelease.RELEASED
    }
}
