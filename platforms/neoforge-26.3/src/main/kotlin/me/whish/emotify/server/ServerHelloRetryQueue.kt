package me.whish.emotify.server

import java.util.LinkedHashMap
import java.util.UUID

internal class ServerHelloRetryQueue {
    private var retries = LinkedHashMap<UUID, Retry>()

    val size: Int
        get() = retries.size

    fun schedule(playerId: UUID, connectionId: Long, attemptsRemaining: Int) {
        require(connectionId > 0L) { "Server connection ID must be positive: $connectionId" }
        require(attemptsRemaining > 0) { "Server hello attempts must be positive: $attemptsRemaining" }
        retries[playerId] = Retry(connectionId, attemptsRemaining)
    }

    fun remove(playerId: UUID, connectionId: Long): Boolean {
        val retry = retries[playerId] ?: return false
        if (retry.connectionId != connectionId) {
            return false
        }
        return retries.remove(playerId, retry)
    }

    fun drain(visitor: (UUID, Long, Int) -> Unit) {
        if (retries.isEmpty()) {
            return
        }
        val drained = retries
        retries = LinkedHashMap()
        for ((playerId, retry) in drained) {
            visitor(playerId, retry.connectionId, retry.attemptsRemaining)
        }
    }

    fun clear() {
        retries.clear()
    }

    private data class Retry(
        val connectionId: Long,
        val attemptsRemaining: Int,
    )
}
