package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FavoriteEmotionStoreTest : FunSpec({
    val happy = EmotionId.of("emotify:happy")
    val sad = EmotionId.of("emotify:sad")
    val dog = EmotionId.of("emotify:dog")

    test("first launch uses the six manifest defaults") {
        val store = FavoriteEmotionStore.withDefaults()

        store.snapshot shouldBe BuiltInEmotionManifest.defaultFavoriteIds.toSet()
        store.orderedIds() shouldContainExactly listOf(
            happy,
            EmotionId.of("emotify:love"),
            EmotionId.of("emotify:surprised"),
            EmotionId.of("emotify:confused"),
            sad,
            EmotionId.of("emotify:angry"),
        )
    }

    test("favorite toggle creates immutable ordered snapshots") {
        val store = FavoriteEmotionStore.from(listOf(happy, sad))
        val before = store.snapshot

        store.toggle(happy) shouldBe FavoriteToggleResult.REMOVED
        store.toggle(dog) shouldBe FavoriteToggleResult.ADDED

        before shouldBe setOf(happy, sad)
        store.orderedIds() shouldContainExactly listOf(sad, dog)
    }

    test("unknown valid configuration values survive built-in favorite changes") {
        val external = EmotionId.of("external:unknown")
        val store = FavoriteEmotionStore.from(
            listOf(happy, happy, external, dog),
        )

        store.orderedIds() shouldContainExactly listOf(happy, dog, external)
        store.toggle(external) shouldBe FavoriteToggleResult.UNKNOWN_EMOTION
        store.toggle(happy) shouldBe FavoriteToggleResult.REMOVED
        store.orderedIds() shouldContainExactly listOf(dog, external)
    }

    test("all favorites can be removed") {
        val store = FavoriteEmotionStore.from(listOf(happy))

        store.toggle(happy) shouldBe FavoriteToggleResult.REMOVED
        store.snapshot shouldBe emptySet()
    }
})
