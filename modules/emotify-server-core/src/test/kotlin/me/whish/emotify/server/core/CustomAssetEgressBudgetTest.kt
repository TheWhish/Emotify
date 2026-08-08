package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker

@Suppress("unused")
class CustomAssetEgressBudgetTest : FunSpec({
    test("one maximum encoded transfer including every chunk header fits atomically") {
        val budget = CustomAssetEgressBudget(
            burstBytes = CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES,
            refillBytesPerSecond = 1,
            timeSource = FakeMonotonicTimeSource(),
        )

        budget.tryReserve(CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES) shouldBe true
        budget.tryReserve(1) shouldBe false
    }

    test("wire sizes above the protocol maximum fail fast") {
        val budget = CustomAssetEgressBudget(timeSource = FakeMonotonicTimeSource())

        shouldThrow<IllegalArgumentException> {
            budget.tryReserve(CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES + 1)
        }
    }
})
