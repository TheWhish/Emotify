package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.protocol.EventSequence

@Suppress("unused")
class ServerEventSequenceTest : FunSpec({
    test("sequence is monotonic and exhausts without wrapping") {
        val sequence = ServerEventSequence(Long.MAX_VALUE - 1L)

        sequence.hasCapacity() shouldBe true
        sequence.nextOrNull() shouldBe EventSequence.of(Long.MAX_VALUE)
        sequence.hasCapacity() shouldBe false
        sequence.nextOrNull() shouldBe null
    }

    test("reset starts a new process sequence at one") {
        val sequence = ServerEventSequence()
        sequence.nextOrNull() shouldBe EventSequence.of(1L)
        sequence.nextOrNull() shouldBe EventSequence.of(2L)

        sequence.reset()

        sequence.nextOrNull() shouldBe EventSequence.of(1L)
    }

    test("negative initial sequence is rejected") {
        shouldThrow<IllegalArgumentException> { ServerEventSequence(-1L) }
    }
})
