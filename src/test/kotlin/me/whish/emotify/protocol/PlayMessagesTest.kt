package me.whish.emotify.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.EmotionId

class PlayMessagesTest : FunSpec({
    test("play identity and sequence enforce positive wire ranges") {
        RuntimeEntityId.of(1).value shouldBe 1
        RuntimeEntityId.of(Int.MAX_VALUE).value shouldBe Int.MAX_VALUE
        EventSequence.of(1).value shouldBe 1L
        EventSequence.of(Long.MAX_VALUE).value shouldBe Long.MAX_VALUE

        shouldThrow<IllegalArgumentException> { RuntimeEntityId.of(0) }
        shouldThrow<IllegalArgumentException> { RuntimeEntityId.of(-1) }
        shouldThrow<IllegalArgumentException> { EventSequence.of(0) }
        shouldThrow<IllegalArgumentException> { EventSequence.of(-1) }
    }

    test("play contains immutable source identity sequence and emotion") {
        val play = EmotionPlay(
            RuntimeEntityId.of(300),
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            EventSequence.of(300),
            EmotionId.of("emotify:happy"),
        )

        play shouldBe play.copy()
    }
})
