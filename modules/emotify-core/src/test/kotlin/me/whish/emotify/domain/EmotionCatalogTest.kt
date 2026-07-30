package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionCatalogTest : FunSpec({
    test("catalog owns an immutable snapshot") {
        val mutableSource = mutableListOf(EmotionId.of("emotify:happy"))
        val catalog = EmotionCatalog.of(mutableSource)

        mutableSource += EmotionId.of("emotify:sad")

        catalog.ids.map(EmotionId::value) shouldContainExactly listOf("emotify:happy")
        shouldThrow<UnsupportedOperationException> {
            (catalog.ids as MutableList).add(EmotionId.of("emotify:angry"))
        }
    }

    test("catalog rejects duplicate IDs") {
        val duplicate = EmotionId.of("emotify:happy")

        shouldThrow<IllegalArgumentException> {
            EmotionCatalog.of(listOf(duplicate, duplicate))
        }
    }

    test("catalog membership is explicit") {
        val catalog = EmotionCatalog.of(listOf(EmotionId.of("emotify:love")))

        catalog.contains(EmotionId.of("emotify:love")) shouldBe true
        catalog.contains(EmotionId.of("other:love")) shouldBe false
    }

    test("catalog accepts the bounded five hundred and twelve entry future capacity") {
        val ids = List(EmotionCatalog.MAX_SIZE) { index ->
            EmotionId.of("emotify:emotion_$index")
        }

        EmotionCatalog.of(ids).ids.size shouldBe 512
    }

    test("catalog rejects entries beyond the bounded future capacity") {
        val ids = List(EmotionCatalog.MAX_SIZE + 1) { index ->
            EmotionId.of("emotify:emotion_$index")
        }

        shouldThrow<IllegalArgumentException> {
            EmotionCatalog.of(ids)
        }
    }
})
