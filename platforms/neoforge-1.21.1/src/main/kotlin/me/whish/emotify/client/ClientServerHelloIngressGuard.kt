package me.whish.emotify.client

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.protocol.ServerHelloEnvelope

class ClientServerHelloIngressGuard(
    timeSource: MonotonicTimeSource,
) {
    private val refreshes = TokenBucket(
        capacity = REFRESH_BURST_CAPACITY,
        refillTokensPerSecond = REFRESH_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private var activeConnectionId = NO_CONNECTION
    private var receivedInitialEnvelope = false
    private var terminalFailure = false

    fun begin(connectionId: Long) {
        require(connectionId > NO_CONNECTION) { "Client connection ID must be positive: $connectionId" }
        activeConnectionId = connectionId
        receivedInitialEnvelope = false
        terminalFailure = false
        refreshes.reset()
    }

    fun tryAdmit(connectionId: Long, envelope: ServerHelloEnvelope): Boolean {
        if (activeConnectionId != connectionId || terminalFailure) {
            return false
        }
        if (envelope === ServerHelloEnvelope.DuplicateEmotionIds) {
            receivedInitialEnvelope = true
            terminalFailure = true
            return true
        }
        if (!receivedInitialEnvelope) {
            receivedInitialEnvelope = true
            return true
        }
        return refreshes.tryConsume()
    }

    fun disconnect(connectionId: Long) {
        if (activeConnectionId != connectionId) {
            return
        }
        activeConnectionId = NO_CONNECTION
        receivedInitialEnvelope = false
        terminalFailure = false
        refreshes.reset()
    }

    companion object {
        const val REFRESH_BURST_CAPACITY = 2
        const val REFRESH_TOKENS_PER_SECOND = 1

        private const val NO_CONNECTION = 0L
    }
}
