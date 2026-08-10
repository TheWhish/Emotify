package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey

@Suppress("unused")
class PaperIngressGateTest : FunSpec({
    test("one player can own only one pending main thread submission") {
        val gate = PaperIngressGate(512)
        val connection = connection(UUID.randomUUID(), 1)
        val lease = gate.tryAcquire(connection)

        (lease != null) shouldBe true
        repeat(10_000) {
            gate.tryAcquire(connection) shouldBe null
        }
        gate.outstandingCount shouldBe 1

        lease?.release()
        gate.outstandingCount shouldBe 0
        (gate.tryAcquire(connection) != null) shouldBe true
    }

    test("process wide outstanding work is bounded and clear invalidates stale leases") {
        val gate = PaperIngressGate(512)
        val leases = List(512) { index -> gate.tryAcquire(connection(UUID.randomUUID(), index + 1L)) }

        leases.all { lease -> lease != null } shouldBe true
        gate.tryAcquire(connection(UUID.randomUUID(), 513)) shouldBe null
        gate.outstandingCount shouldBe 512

        gate.clear()
        gate.outstandingCount shouldBe 0
        leases.forEach { lease -> lease?.release() }
        gate.outstandingCount shouldBe 0
    }

    test("chunk lane leaves one bounded serial slot for the final custom selection") {
        val gate = PaperIngressGate(32)
        val connection = connection(UUID.randomUUID(), 1)
        val chunkLeases = List(18) {
            gate.tryAcquire(
                connection,
                PaperIngressLane.CUSTOM_ASSET_CHUNK,
                maximumForLane = 18,
            )
        }
        val selectionLease = gate.tryAcquire(connection)

        chunkLeases.all { lease -> lease != null } shouldBe true
        (selectionLease != null) shouldBe true
        gate.tryAcquire(connection) shouldBe null
        gate.tryAcquire(
            connection,
            PaperIngressLane.CUSTOM_ASSET_CHUNK,
            maximumForLane = 18,
        ) shouldBe null
        gate.outstandingCount shouldBe 19

        selectionLease?.release()
        gate.outstandingCount shouldBe 18
        chunkLeases.forEach { lease -> lease?.release() }
        gate.outstandingCount shouldBe 0

        val staleChunk = gate.tryAcquire(connection, PaperIngressLane.CUSTOM_ASSET_CHUNK, 18)
        val staleSelection = gate.tryAcquire(connection)
        gate.clear()
        staleChunk?.release()
        staleSelection?.release()
        gate.outstandingCount shouldBe 0
    }

    test("a stale lease cannot release a reservation created after clear") {
        val gate = PaperIngressGate(512)
        val playerId = UUID.randomUUID()
        val staleLease = gate.tryAcquire(connection(playerId, 1))
        gate.clear()
        val activeLease = gate.tryAcquire(connection(playerId, 2))

        staleLease?.release()
        gate.outstandingCount shouldBe 1

        activeLease?.release()
        gate.outstandingCount shouldBe 0
    }

    test("reconfiguration preserves leases and blocks new work below the lowered ceiling") {
        val gate = PaperIngressGate(4)
        val first = gate.tryAcquire(connection(UUID.randomUUID(), 1))
        val second = gate.tryAcquire(connection(UUID.randomUUID(), 2))

        gate.reconfigure(1)

        gate.outstandingCount shouldBe 2
        gate.tryAcquire(connection(UUID.randomUUID(), 3)) shouldBe null
        first?.release()
        gate.tryAcquire(connection(UUID.randomUUID(), 4)) shouldBe null
        second?.release()
        (gate.tryAcquire(connection(UUID.randomUUID(), 5)) != null) shouldBe true
    }
})

private fun connection(playerId: UUID, connectionId: Long): ConnectionKey =
    ConnectionKey(playerId, ConnectionId.of(connectionId))
