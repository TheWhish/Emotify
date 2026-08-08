package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels

@Suppress("unused")
class CustomEmojiLibraryBudgetTest : FunSpec({
    test("retains unique assets within the aggregate byte budget") {
        val first = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it }))
        val second = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it + 1 }))
        val budget = CustomEmojiLibraryBudget(maximumRetainedBytes = CustomEmojiLibraryBudget.MAXIMUM_SINGLE_ASSET_BYTES)

        budget.admit(first.id, 256) shouldBe CustomEmojiLibraryAdmission.ACCEPTED
        budget.admit(first.id, 256) shouldBe CustomEmojiLibraryAdmission.DUPLICATE
        budget.admit(second.id, CustomEmojiLibraryBudget.MAXIMUM_SINGLE_ASSET_BYTES) shouldBe
            CustomEmojiLibraryAdmission.CAPACITY_REACHED
    }
})
