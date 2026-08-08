package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

@Suppress("unused")
class RemoteCustomEmojiRetentionTest : FunSpec({
    fun id(seed: Int): CustomEmojiId = CustomEmojiAsset.create(
        CustomEmojiPixels.of(IntArray(64) { seed + it }),
    ).id

    test("render-style lookups do not change deterministic retention order") {
        val retention = RemoteCustomEmojiRetention(maximumAssets = 2)
        val first = id(1)
        val second = id(2)
        val third = id(3)

        retention.retain(first, 256)
        retention.retain(second, 256)
        repeat(100) {
            retention.contains(first) shouldBe true
        }

        retention.retain(third, 256).shouldContainExactly(first)
        retention.contains(first) shouldBe false
        retention.contains(second) shouldBe true
        retention.contains(third) shouldBe true
    }

    test("raw byte pressure evicts the oldest retained IDs") {
        val retention = RemoteCustomEmojiRetention(maximumAssets = 256, maximumRawBytes = 500_000)
        val first = id(1)
        val second = id(2)

        retention.retain(first, 300_000)
        retention.retain(second, 300_000).shouldContainExactly(first)

        retention.contains(first) shouldBe false
        retention.contains(second) shouldBe true
    }

    test("rejected size mismatch leaves retention accounting unchanged") {
        val retention = RemoteCustomEmojiRetention(maximumAssets = 256, maximumRawBytes = 500_000)
        val first = id(1)
        val second = id(2)
        val third = id(3)

        retention.retain(first, 300_000)
        shouldThrow<IllegalArgumentException> {
            retention.retain(first, 200_000)
        }
        retention.retain(second, 250_000).shouldContainExactly(first)
        retention.retain(third, 200_000) shouldBe emptyList()

        retention.contains(first) shouldBe false
        retention.contains(second) shouldBe true
        retention.contains(third) shouldBe true
    }
})
