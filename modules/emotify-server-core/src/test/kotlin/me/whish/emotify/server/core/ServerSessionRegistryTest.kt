package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class ServerSessionRegistryTest : FunSpec({
    fun registry(): ServerSessionRegistry = ServerSessionRegistry(
        TEST_CAPABILITIES,
        1_200.milliseconds,
        FakeMonotonicTimeSource(),
    )

    test("reconnect replaces state and stale close cannot remove it") {
        val registry = registry()
        val playerId = UUID(0L, 1L)
        val firstConnection = testConnection(1L, playerId)
        val secondConnection = testConnection(2L, playerId)
        val first = registry.open(firstConnection)
        first.receiveClientHello(TEST_CLIENT_HELLO)
        first.commitSelection()

        val second = registry.open(secondConnection)

        second.handshakeState shouldBe ServerHandshakeState.Pending
        registry.get(firstConnection).shouldBeNull()
        registry.close(firstConnection) shouldBe false
        registry.get(secondConnection) shouldBe second
        registry.activeConnection(playerId) shouldBe secondConnection
        registry.size shouldBe 1
    }

    test("opening the same active connection fails fast") {
        val registry = registry()
        val connection = testConnection(1L)
        registry.open(connection)

        shouldThrow<IllegalStateException> { registry.open(connection) }
    }

    test("close and clear report exact lifecycle state") {
        val registry = registry()
        val first = testConnection(1L)
        val second = testConnection(2L)
        registry.open(first)
        registry.open(second)

        registry.close(first) shouldBe true
        registry.close(first) shouldBe false
        registry.clear() shouldBe 1
        registry.clear() shouldBe 0
        registry.size shouldBe 0
    }

    test("randomized reconnect lifecycle matches a scalar reference model") {
        val registry = registry()
        val random = Random(0x5E5510)
        val players = List(16) { UUID(0L, it.toLong() + 1L) }
        val active = HashMap<UUID, ConnectionKey>()
        var nextConnectionId = 1L

        repeat(10_000) {
            val playerId = players[random.nextInt(players.size)]
            when (random.nextInt(3)) {
                0 -> {
                    val connection = testConnection(nextConnectionId++, playerId)
                    registry.open(connection)
                    active[playerId] = connection
                }
                1 -> {
                    val expected = active[playerId]
                    val connection = if (expected != null && random.nextBoolean()) {
                        expected
                    } else {
                        testConnection(nextConnectionId++, playerId)
                    }
                    registry.close(connection) shouldBe (expected == connection)
                    if (expected == connection) {
                        active.remove(playerId)
                    }
                }
                else -> {
                    val expected = active[playerId]
                    val connection = if (expected != null && random.nextBoolean()) {
                        expected
                    } else {
                        testConnection(nextConnectionId++, playerId)
                    }
                    (registry.get(connection) != null) shouldBe (expected == connection)
                    registry.activeConnection(playerId) shouldBe expected
                }
            }
            registry.size shouldBe active.size
        }
    }
})
