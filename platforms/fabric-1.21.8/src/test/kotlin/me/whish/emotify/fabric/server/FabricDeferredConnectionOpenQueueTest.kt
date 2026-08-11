package me.whish.emotify.fabric.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

@Suppress("unused")
class FabricDeferredConnectionOpenQueueTest : FunSpec({
    test("deferred connections drain once in insertion order") {
        val queue = FabricDeferredConnectionOpenQueue()
        val firstPlayerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPlayerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val firstConnection = Any()
        val secondConnection = Any()
        val drained = mutableListOf<Pair<UUID, Any>>()

        queue.defer(firstPlayerId, firstConnection)
        queue.defer(secondPlayerId, secondConnection)
        queue.drain { playerId, connectionIdentity, _ ->
            drained += playerId to connectionIdentity
        }

        drained.shouldContainExactly(
            firstPlayerId to firstConnection,
            secondPlayerId to secondConnection,
        )
        queue.size shouldBe 0
    }

    test("a reconnect replaces the stale deferred connection for the same player") {
        val queue = FabricDeferredConnectionOpenQueue()
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val staleConnection = Any()
        val currentConnection = Any()
        val drained = mutableListOf<Any>()

        queue.defer(playerId, staleConnection)
        queue.defer(playerId, currentConnection)
        queue.drain { _, connectionIdentity, _ -> drained += connectionIdentity }

        drained.shouldContainExactly(currentConnection)
    }

    test("clear discards every deferred connection") {
        val queue = FabricDeferredConnectionOpenQueue()
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000004")

        queue.defer(playerId, Any())
        queue.clear()

        queue.size shouldBe 0
    }

    test("a connection deferred while draining waits for the next drain") {
        val queue = FabricDeferredConnectionOpenQueue()
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val connection = Any()
        val drained = mutableListOf<Any>()

        queue.defer(playerId, connection)
        queue.drain { currentPlayerId, connectionIdentity, attemptsRemaining ->
            drained += connectionIdentity
            queue.defer(currentPlayerId, connectionIdentity, attemptsRemaining)
        }

        drained.shouldContainExactly(connection)
        queue.size shouldBe 1

        queue.drain { _, connectionIdentity, _ -> drained += connectionIdentity }

        drained.shouldContainExactly(connection, connection)
        queue.size shouldBe 0
    }
})
