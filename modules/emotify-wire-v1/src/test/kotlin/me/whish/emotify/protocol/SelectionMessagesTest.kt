package me.whish.emotify.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason

@Suppress("unused")
class SelectionMessagesTest : FunSpec({
    test("selection contains only one emotion ID") {
        val emotionId = EmotionId.of("emotify:happy")

        EmotionSelection(emotionId) shouldBe EmotionSelection(emotionId)
    }

    test("known rejection reasons keep explicit stable wire codes") {
        SelectionRejectionCode.from(SelectionRejectionReason.COOLDOWN).value shouldBe 0
        SelectionRejectionCode.from(SelectionRejectionReason.SERVER_DISABLED).value shouldBe 1
        SelectionRejectionCode.from(SelectionRejectionReason.EMOTION_DISABLED).value shouldBe 2
        SelectionRejectionCode.from(SelectionRejectionReason.PLAYER_STATE).value shouldBe 3
        SelectionRejectionCode.from(SelectionRejectionReason.SERVER_BUSY).value shouldBe 4
    }

    test("unknown rejection code remains decodable") {
        val code = SelectionRejectionCode(255)

        code.knownReason shouldBe null
    }

    test("known rejection codes map back to their stable reasons") {
        SelectionRejectionReason.entries.forEach { reason ->
            val code = SelectionRejectionCode.from(reason)

            code.knownReason shouldBe reason
        }
    }

    test("rejection code enforces unsigned byte boundaries") {
        SelectionRejectionCode(0).value shouldBe 0
        SelectionRejectionCode(255).value shouldBe 255

        shouldThrow<IllegalArgumentException> { SelectionRejectionCode(-1) }
        shouldThrow<IllegalArgumentException> { SelectionRejectionCode(256) }
    }

    test("rejection retry delay enforces protocol limits") {
        SelectionRejected(SelectionRejectionCode(0), 0).retryAfterMillis shouldBe 0
        SelectionRejected(SelectionRejectionCode(0), 10_000).retryAfterMillis shouldBe 10_000

        shouldThrow<IllegalArgumentException> {
            SelectionRejected(SelectionRejectionCode(0), -1)
        }
        shouldThrow<IllegalArgumentException> {
            SelectionRejected(SelectionRejectionCode(0), 10_001)
        }
    }
})
