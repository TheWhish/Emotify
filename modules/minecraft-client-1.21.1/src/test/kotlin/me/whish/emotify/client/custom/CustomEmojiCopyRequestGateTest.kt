package me.whish.emotify.client.custom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import me.whish.emotify.domain.CustomEmojiId

@Suppress("unused")
class CustomEmojiCopyRequestGateTest : FunSpec({
    val first = CustomEmojiId(1L, 2L, 3L)
    val second = CustomEmojiId(4L, 5L, 6L)

    test("only an accepted copy request owns and consumes the input") {
        val gate = CustomEmojiCopyRequestGate()

        gate.tryBegin(first) shouldBe true
        gate.tryBegin(first) shouldBe false
        gate.tryBegin(second) shouldBe false
        gate.complete(second) shouldBe false
        gate.tryBegin(second) shouldBe false
        gate.complete(first) shouldBe true
        gate.tryBegin(second) shouldBe true
    }

    test("synchronous copy scheduling failure releases request ownership") {
        val gate = CustomEmojiCopyRequestGate()

        shouldThrow<RejectedExecutionException> {
            beginCustomEmojiCopy<String>(gate, first, alreadyPresent = false) {
                throw RejectedExecutionException("closed")
            }
        }
        beginCustomEmojiCopy(gate, first, alreadyPresent = false) {
            CompletableFuture.completedFuture("saved")
        }.shouldNotBeNull().join() shouldBe "saved"
    }

    test("existing and active copies are not scheduled twice") {
        val gate = CustomEmojiCopyRequestGate()
        var submissions = 0

        beginCustomEmojiCopy(gate, first, alreadyPresent = true) {
            submissions += 1
            CompletableFuture.completedFuture(Unit)
        } shouldBe null
        beginCustomEmojiCopy(gate, first, alreadyPresent = false) {
            submissions += 1
            CompletableFuture.completedFuture(Unit)
        }.shouldNotBeNull()
        beginCustomEmojiCopy(gate, first, alreadyPresent = false) {
            submissions += 1
            CompletableFuture.completedFuture(Unit)
        } shouldBe null

        submissions shouldBe 1
    }
})
