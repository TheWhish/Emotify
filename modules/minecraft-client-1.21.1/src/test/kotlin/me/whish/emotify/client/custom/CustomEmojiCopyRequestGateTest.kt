package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
})
