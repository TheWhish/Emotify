package me.whish.emotify.paper.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

@Suppress("unused")
class PaperConfigurationApplyTransactionTest : FunSpec({
    test("failed apply restores the previously active subsystem configuration") {
        val applied = ArrayList<Int>()
        var subsystemConfiguration = 1
        val transaction = PaperConfigurationApplyTransaction<Int, Int>(
            current = { 1 },
            apply = { replacement ->
                applied += replacement
                subsystemConfiguration = replacement
                if (replacement == 2) {
                    throw IllegalStateException("apply failed")
                }
                replacement
            },
        )

        shouldThrow<IllegalStateException> {
            transaction.execute(2)
        }

        applied.shouldContainExactly(2, 1)
        subsystemConfiguration shouldBe 1
    }

    test("rollback failure is retained as suppressed diagnostic context") {
        val transaction = PaperConfigurationApplyTransaction<Int, Int>(
            current = { 1 },
            apply = { replacement ->
                throw IllegalStateException("failed $replacement")
            },
        )

        val failure = shouldThrow<IllegalStateException> {
            transaction.execute(2)
        }

        failure.message shouldBe "failed 2"
        failure.suppressed.single().message shouldBe "failed 1"
    }
})
