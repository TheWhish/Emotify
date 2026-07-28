package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmotionIdTest : FunSpec({
    test("accepts the complete wire character set") {
        val raw = "namespace_1.2-3:path/to_emotion.4-5"

        EmotionId.parse(raw)?.value shouldBe raw
    }

    test("accepts exactly sixty four ASCII bytes") {
        val raw = "a:${"b".repeat(62)}"

        raw.length shouldBe EmotionId.MAX_ENCODED_LENGTH
        EmotionId.parse(raw)?.value shouldBe raw
    }

    listOf(
        "",
        "emotify",
        ":happy",
        "emotify:",
        "Emotify:happy",
        "emotify:happy face",
        "emotify:счастье",
        "a:${"b".repeat(63)}",
    ).forEach { raw ->
        test("rejects invalid ID '$raw'") {
            EmotionId.parse(raw) shouldBe null
        }
    }

    test("required construction fails fast") {
        shouldThrow<IllegalArgumentException> {
            EmotionId.of("invalid")
        }
    }
})
