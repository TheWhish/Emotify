package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import me.whish.emotify.domain.CustomEmojiId

@Suppress("unused")
class CustomEmojiReconciliationTest : FunSpec({
    val firstPath = Path.of("emoji", "first.png")
    val invalidPath = Path.of("emoji", "broken.png")
    val firstId = CustomEmojiId(1L, 2L, 3L).emotionId
    val secondId = CustomEmojiId(4L, 5L, 6L).emotionId

    test("an unrelated invalid file does not preserve a removed custom reference") {
        val result = reconcileCustomEmojiReferences(
            previousSourceEmotionIds = mapOf(firstPath to firstId),
            decodedSourceEmotionIds = emptyMap(),
            unavailableSourcePaths = setOf(invalidPath),
            directoryLimitReached = false,
        )

        result.sourceEmotionIds shouldBe emptyMap()
        result.presentEmotionIds shouldBe emptySet()
        result.removalSafe shouldBe true
    }

    test("a transient failure at the same path preserves its previous reference") {
        val result = reconcileCustomEmojiReferences(
            previousSourceEmotionIds = mapOf(firstPath to firstId),
            decodedSourceEmotionIds = emptyMap(),
            unavailableSourcePaths = setOf(firstPath),
            directoryLimitReached = false,
        )

        result.sourceEmotionIds shouldBe mapOf(firstPath to firstId)
        result.presentEmotionIds shouldBe setOf(firstId)
        result.removalSafe shouldBe true
    }

    test("a successfully decoded source replaces the previous identity at the same path") {
        val result = reconcileCustomEmojiReferences(
            previousSourceEmotionIds = mapOf(firstPath to firstId),
            decodedSourceEmotionIds = mapOf(firstPath to secondId),
            unavailableSourcePaths = setOf(firstPath),
            directoryLimitReached = false,
        )

        result.sourceEmotionIds shouldBe mapOf(firstPath to secondId)
        result.presentEmotionIds shouldBe setOf(secondId)
        result.removalSafe shouldBe true
    }

    test("a decoded source remains present even when library admission rejects its entry") {
        val result = reconcileCustomEmojiReferences(
            previousSourceEmotionIds = emptyMap(),
            decodedSourceEmotionIds = mapOf(firstPath to firstId),
            unavailableSourcePaths = emptySet(),
            directoryLimitReached = false,
        )

        result.sourceEmotionIds shouldBe mapOf(firstPath to firstId)
        result.presentEmotionIds shouldBe setOf(firstId)
        result.removalSafe shouldBe true
    }

    test("the directory scan limit remains globally unsafe and retains the previous index") {
        val result = reconcileCustomEmojiReferences(
            previousSourceEmotionIds = mapOf(firstPath to firstId),
            decodedSourceEmotionIds = mapOf(invalidPath to secondId),
            unavailableSourcePaths = emptySet(),
            directoryLimitReached = true,
        )

        result.sourceEmotionIds shouldBe
            mapOf(
                firstPath to firstId,
                invalidPath to secondId,
            )
        result.presentEmotionIds shouldBe setOf(firstId, secondId)
        result.removalSafe shouldBe false
    }
})
