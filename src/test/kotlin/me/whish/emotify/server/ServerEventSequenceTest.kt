package me.whish.emotify.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.protocol.EventSequence

class ServerEventSequenceTest : FunSpec({
    test("sequence is global monotonic and exhausts without wrapping") {
        val sequence = ServerEventSequence(Long.MAX_VALUE - 1)

        sequence.nextOrNull() shouldBe EventSequence.of(Long.MAX_VALUE)
        sequence.nextOrNull() shouldBe null
    }

    test("server stop reset starts the next process generation from one") {
        val sequence = ServerEventSequence()

        sequence.nextOrNull() shouldBe EventSequence.of(1)
        sequence.nextOrNull() shouldBe EventSequence.of(2)
        sequence.reset()
        sequence.nextOrNull() shouldBe EventSequence.of(1)
    }
})
