package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import me.whish.emotify.domain.CustomEmojiId

@Suppress("unused")
class CustomEmojiReloadCoordinatorTest : FunSpec({
    test("coalesces concurrent requests into one serial follow-up and keeps only the latest callback") {
        val coordinator = CustomEmojiReloadCoordinator()
        var completed = 0

        coordinator.request { completed += 1 } shouldBe true
        repeat(20) {
            coordinator.request { completed += 1 } shouldBe false
        }

        coordinator.complete(success = true) shouldBe CustomEmojiReloadCompletion.FollowUp
        val completion = coordinator.complete(success = true)
            .shouldBeInstanceOf<CustomEmojiReloadCompletion.Finished>()
        completion.callbacks.forEach { callback -> callback() }
        completed shouldBe 1
        coordinator.request {} shouldBe true
    }

    test("unstable snapshot retries without publishing callbacks") {
        val coordinator = CustomEmojiReloadCoordinator()
        var completed = false

        coordinator.request { completed = true } shouldBe true
        coordinator.complete(success = false, retry = true) shouldBe CustomEmojiReloadCompletion.FollowUp
        coordinator.complete(success = true).shouldBeInstanceOf<CustomEmojiReloadCompletion.Finished>()
            .callbacks.single().invoke()
        completed shouldBe true
    }

    test("subscription observes the active load without scheduling another decode") {
        val coordinator = CustomEmojiReloadCoordinator()
        var completed = false

        coordinator.request {} shouldBe true
        coordinator.subscribe { completed = true } shouldBe true
        val completion = coordinator.complete(success = true)
            .shouldBeInstanceOf<CustomEmojiReloadCompletion.Finished>()
        completion.callbacks.single().invoke()

        completed shouldBe true
        coordinator.isInFlight() shouldBe false
    }

    test("repeated subscriptions keep only the latest callback without requesting a follow-up") {
        val coordinator = CustomEmojiReloadCoordinator()
        var completed = 0

        coordinator.request { completed = -1 } shouldBe true
        repeat(20) { index ->
            coordinator.subscribe { completed = index + 1 } shouldBe true
        }

        val completion = coordinator.complete(success = true)
            .shouldBeInstanceOf<CustomEmojiReloadCompletion.Finished>()
        completion.callbacks.single().invoke()

        completed shouldBe 20
        coordinator.isInFlight() shouldBe false
    }

    test("a retained completion observer survives transient subscriptions and receives failure") {
        val coordinator = CustomEmojiReloadCoordinator()
        val gate = CustomEmojiCopyRequestGate()
        val origin = CustomEmojiId(1L, 2L, 3L)
        val nextOrigin = CustomEmojiId(4L, 5L, 6L)
        var publicationResult: Boolean? = null

        gate.tryBegin(origin) shouldBe true
        coordinator.requestWithResult { success ->
            publicationResult = success
            gate.complete(origin) shouldBe true
        } shouldBe true
        repeat(20) { coordinator.subscribe {} shouldBe true }
        gate.tryBegin(nextOrigin) shouldBe false

        val completion = coordinator.complete(success = false)
            .shouldBeInstanceOf<CustomEmojiReloadCompletion.Finished>()
        completion.resultCallbacks.forEach { callback -> callback(completion.success) }
        completion.callbacks.forEach { callback -> callback() }

        publicationResult shouldBe false
        gate.tryBegin(nextOrigin) shouldBe true
        coordinator.isInFlight() shouldBe false
    }
})
