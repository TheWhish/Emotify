package me.whish.emotify.client

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

class ClientPlayIngressGuard(
    timeSource: MonotonicTimeSource,
) {
    private val plays = TokenBucket(
        capacity = PLAY_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private var activeConnectionId = NO_CONNECTION

    fun begin(connectionId: Long) {
        require(connectionId > NO_CONNECTION) { "Client connection ID must be positive: $connectionId" }
        activeConnectionId = connectionId
        plays.reset()
    }

    fun tryAdmit(connectionId: Long): Boolean =
        activeConnectionId == connectionId && plays.tryConsume()

    fun disconnect(connectionId: Long) {
        if (activeConnectionId != connectionId) {
            return
        }
        activeConnectionId = NO_CONNECTION
        plays.reset()
    }

    companion object {
        const val PLAY_BURST_CAPACITY = 32
        const val PLAY_TOKENS_PER_SECOND = 16

        private const val NO_CONNECTION = 0L
    }
}
