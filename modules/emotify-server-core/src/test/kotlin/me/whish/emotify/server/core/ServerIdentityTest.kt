package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.protocol.RuntimeEntityId

@Suppress("unused")
class ServerIdentityTest : FunSpec({
    test("connection IDs accept only positive values") {
        ConnectionId.parse(-1L) shouldBe null
        ConnectionId.parse(0L) shouldBe null
        ConnectionId.parse(1L) shouldBe ConnectionId.of(1L)
        shouldThrow<IllegalArgumentException> { ConnectionId.of(Long.MIN_VALUE) }
    }

    test("connection keys distinguish reconnect generations") {
        val playerId = testConnection(1L).playerId

        (testConnection(1L, playerId) == testConnection(2L, playerId)) shouldBe false
        testConnection(1L, playerId) shouldBe testConnection(1L, playerId)
    }

    test("player publication state is derived from an immutable snapshot") {
        val ready = testPlayer(testConnection(1L))

        ready.canPublish shouldBe true
        ready.copy(alive = false).canPublish shouldBe false
        ready.copy(spectator = true).canPublish shouldBe false
        ready.copy(invisible = true).canPublish shouldBe false
        ready.copy(entityId = RuntimeEntityId.of(2)).canPublish shouldBe true
    }
})
