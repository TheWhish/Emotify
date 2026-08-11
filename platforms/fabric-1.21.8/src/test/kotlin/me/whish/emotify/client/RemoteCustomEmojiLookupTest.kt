package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels

@Suppress("unused")
class RemoteCustomEmojiLookupTest : FunSpec({
    fun customEmojiId(seed: Int) = CustomEmojiAsset.create(
        CustomEmojiPixels.of(IntArray(64) { index -> seed + index }),
    ).id

    test("all lookup indexes stay synchronized across add eviction and clear") {
        val lookup = RemoteCustomEmojiLookup<String, String>()
        val first = customEmojiId(1)
        val second = customEmojiId(2)

        lookup.add(first, "texture:first", "entry:first", "resource:first")
        lookup.add(second, "texture:second", "entry:second", "resource:second")

        lookup.contains(first) shouldBe true
        lookup.find(first.emotionId) shouldBe "entry:first"
        lookup.resolveTexture("texture:first") shouldBe "resource:first"
        lookup.find(second.emotionId) shouldBe "entry:second"
        lookup.resolveTexture("texture:second") shouldBe "resource:second"

        lookup.remove(first) shouldBe "entry:first"
        lookup.contains(first) shouldBe false
        lookup.find(first.emotionId) shouldBe null
        lookup.resolveTexture("texture:first") shouldBe null
        lookup.find(second.emotionId) shouldBe "entry:second"
        lookup.resolveTexture("texture:second") shouldBe "resource:second"

        lookup.clear().shouldContainExactlyInAnyOrder("entry:second")
        lookup.contains(second) shouldBe false
        lookup.find(second.emotionId) shouldBe null
        lookup.resolveTexture("texture:second") shouldBe null
    }
})
