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
