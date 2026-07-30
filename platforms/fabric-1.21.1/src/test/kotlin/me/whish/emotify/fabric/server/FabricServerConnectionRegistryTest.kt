package me.whish.emotify.fabric.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.util.UUID

@Suppress("unused")
class FabricServerConnectionRegistryTest : FunSpec({
    val playerId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    beforeTest {
        FabricServerConnectionRegistry.clear()
    }

    afterTest {
        FabricServerConnectionRegistry.clear()
    }

    test("connection state accepts only its exact connection identity") {
        val connectionIdentity = Any()
        val state = FabricServerConnectionRegistry.open(playerId, connectionIdentity)

        state.belongsTo(connectionIdentity).shouldBeTrue()
        state.belongsTo(Any()).shouldBeFalse()
    }

    test("reconnected identity survives a late disconnect from the previous connection") {
        val firstIdentity = Any()
        val secondIdentity = Any()
        val first = FabricServerConnectionRegistry.open(playerId, firstIdentity)

        FabricServerConnectionRegistry.current(playerId, firstIdentity) shouldBe first
        FabricServerConnectionRegistry.current(playerId, secondIdentity) shouldBe null
        FabricServerConnectionRegistry.close(playerId, first).shouldBeTrue()
        val second = FabricServerConnectionRegistry.open(playerId, secondIdentity)

        FabricServerConnectionRegistry.close(playerId, first).shouldBeFalse()
        FabricServerConnectionRegistry.current(playerId) shouldBe second
        FabricServerConnectionRegistry.current(playerId, firstIdentity) shouldBe null
        FabricServerConnectionRegistry.current(playerId, secondIdentity) shouldBe second
    }

    test("duplicate open fails without replacing the active session") {
        val first = FabricServerConnectionRegistry.open(playerId, Any())

        shouldThrow<IllegalStateException> {
            FabricServerConnectionRegistry.open(playerId, Any())
        }
        FabricServerConnectionRegistry.current(playerId) shouldBe first
    }
})
