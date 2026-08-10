package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class CustomEmojiDescriptorTest : FunSpec({
    val origin = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it })).id

    test("descriptor keeps a normalized bounded display name and stable origin") {
        val descriptor = CustomEmojiDescriptor.create("  Танец e\u0301  ", origin)

        descriptor.displayName shouldBe "Танец é"
        descriptor.originId shouldBe origin
    }

    test("blank control-heavy and oversized names are rejected") {
        shouldThrow<IllegalArgumentException> {
            CustomEmojiDescriptor.create("   ", origin)
        }
        shouldThrow<IllegalArgumentException> {
            CustomEmojiDescriptor.create("bad\nname", origin)
        }
        shouldThrow<IllegalArgumentException> {
            CustomEmojiDescriptor.create("я".repeat(65), origin)
        }
    }

    test("unicode direction overrides separators and malformed surrogates are rejected") {
        val malformedSurrogate = String(charArrayOf('\uD800'))
        listOf(
            "safe\u061Cname",
            "safe\u200Fname",
            "safe\u202Ename",
            "safe\u2066name",
            "safe\u2028name",
            "safe\u2029name",
            "safe${malformedSurrogate}name",
        ).forEach { displayName ->
            shouldThrow<IllegalArgumentException> {
                CustomEmojiDescriptor.create(displayName, origin)
            }
        }
    }

    test("default descriptor uses the content ID as its origin") {
        CustomEmojiDescriptor.default(origin) shouldBe
            CustomEmojiDescriptor.create(CustomEmojiDescriptor.DEFAULT_DISPLAY_NAME, origin)
    }
})
