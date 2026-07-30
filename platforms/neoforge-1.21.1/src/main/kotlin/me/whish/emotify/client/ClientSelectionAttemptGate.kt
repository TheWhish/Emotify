package me.whish.emotify.client

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SELECTION_REJECTION_BURST_CAPACITY
import me.whish.emotify.domain.SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

internal class ClientSelectionAttemptGate(
    timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val attempts = TokenBucket(
        SELECTION_REJECTION_BURST_CAPACITY,
        SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND,
        timeSource,
    )

    fun tryAdmit(): Boolean = attempts.tryConsume()

    fun refund() {
        attempts.refundOne()
    }

    fun reset() {
        attempts.reset()
    }
}
