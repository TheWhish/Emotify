package me.whish.emotify.server

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import java.util.UUID

@Suppress("unused")
class ServerHelloRetryQueueTest {
    @Test
    fun `drain allows a bounded retry to be scheduled for the next tick`() {
        val queue = ServerHelloRetryQueue()
        val playerId = UUID.randomUUID()
        queue.schedule(playerId, connectionId = 1L, attemptsRemaining = 2)
        val visits = mutableListOf<Triple<UUID, Long, Int>>()

        queue.drain { id, connectionId, attemptsRemaining ->
            visits += Triple(id, connectionId, attemptsRemaining)
            queue.schedule(id, connectionId, attemptsRemaining - 1)
        }

        visits shouldBe listOf(Triple(playerId, 1L, 2))
        queue.size shouldBe 1
        queue.drain { id, connectionId, attemptsRemaining ->
            visits += Triple(id, connectionId, attemptsRemaining)
        }
        visits shouldBe listOf(Triple(playerId, 1L, 2), Triple(playerId, 1L, 1))
        queue.size shouldBe 0
    }

    @Test
    fun `stale disconnect cannot remove a replacement connection retry`() {
        val queue = ServerHelloRetryQueue()
        val playerId = UUID.randomUUID()
        queue.schedule(playerId, connectionId = 1L, attemptsRemaining = 2)
        queue.schedule(playerId, connectionId = 2L, attemptsRemaining = 2)

        queue.remove(playerId, connectionId = 1L) shouldBe false
        queue.size shouldBe 1
        queue.remove(playerId, connectionId = 2L) shouldBe true
        queue.size shouldBe 0
    }
}
