package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.state.ClientSelectionSendResult

@Suppress("unused")
class EmotionPickerAvailabilityTest : FunSpec({
    test("spectator and invisible players can browse but cannot publish") {
        EmotionPickerAccessPolicy.decide(
            screenOpen = false,
            worldAvailable = true,
            connectionAvailable = true,
            playerAvailable = true,
            serverContextAvailable = true,
            catalogAvailable = true,
        ) shouldBe EmotionPickerAccessDecision.OPEN

        ClientSelectionEligibility.canPublish(alive = true, spectator = true, invisible = false) shouldBe false
        ClientSelectionEligibility.canPublish(alive = true, spectator = false, invisible = true) shouldBe false
        ClientSelectionEligibility.canPublish(alive = true, spectator = false, invisible = false) shouldBe true
    }

    test("picker access distinguishes server absence from local game conflicts") {
        EmotionPickerAccessPolicy.decide(
            screenOpen = false,
            worldAvailable = true,
            connectionAvailable = true,
            playerAvailable = true,
            serverContextAvailable = false,
            catalogAvailable = true,
        ) shouldBe EmotionPickerAccessDecision.SERVER_UNAVAILABLE
        EmotionPickerAccessPolicy.decide(
            screenOpen = true,
            worldAvailable = true,
            connectionAvailable = true,
            playerAvailable = true,
            serverContextAvailable = true,
            catalogAvailable = true,
        ) shouldBe EmotionPickerAccessDecision.SCREEN_OCCUPIED
        EmotionPickerAccessPolicy.decide(
            screenOpen = false,
            worldAvailable = true,
            connectionAvailable = true,
            playerAvailable = true,
            serverContextAvailable = true,
            catalogAvailable = false,
        ) shouldBe EmotionPickerAccessDecision.EMPTY_CATALOG
    }

    test("frame request drain consumes every queued click as one open request") {
        var remainingClicks = 3
        val requests = EmotionPickerOpenRequests {
            if (remainingClicks == 0) {
                false
            } else {
                remainingClicks -= 1
                true
            }
        }

        requests.drain() shouldBe true
        remainingClicks shouldBe 0
        requests.drain() shouldBe false
    }

    test("player state selection failures use the picker notice translation") {
        ClientSelectionSendResult.PLAYER_STATE.messageTranslationKey() shouldBe "message.emotify.player_state"
        ClientSelectionSendResult.SENT.messageTranslationKey() shouldBe null
    }

    test("custom selection failures identify protocol support and missing local assets") {
        ClientSelectionSendResult.CUSTOM_EMOJIS_UNSUPPORTED.messageTranslationKey() shouldBe
            "message.emotify.custom_emojis_unsupported"
        ClientSelectionSendResult.CUSTOM_EMOJI_MISSING.messageTranslationKey() shouldBe
            "message.emotify.custom_emoji_missing"
        ClientSelectionSendResult.CUSTOM_EMOJIS_DISABLED.messageTranslationKey() shouldBe
            "message.emotify.custom_emojis_disabled"
        ClientSelectionSendResult.CUSTOM_EMOJI_TOO_LARGE.messageTranslationKey() shouldBe
            "message.emotify.custom_emoji_too_large"
    }
})
