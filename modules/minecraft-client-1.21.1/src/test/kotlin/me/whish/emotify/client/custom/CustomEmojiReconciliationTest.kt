package me.whish.emotify.client.custom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class CustomEmojiReconciliationTest : FunSpec({
    test("missing quick slots can be removed after a complete library load") {
        canRemoveMissingCustomQuickSlots(0, 0, 0, false) shouldBe true
    }

    test("partial or failed library loads preserve quick slots") {
        canRemoveMissingCustomQuickSlots(1, 0, 0, false) shouldBe false
        canRemoveMissingCustomQuickSlots(0, 1, 0, false) shouldBe false
        canRemoveMissingCustomQuickSlots(0, 0, 1, false) shouldBe false
        canRemoveMissingCustomQuickSlots(0, 0, 0, true) shouldBe false
    }

    test("invalid counters are rejected") {
        shouldThrow<IllegalArgumentException> {
            canRemoveMissingCustomQuickSlots(-1, 0, 0, false)
        }
    }
})
