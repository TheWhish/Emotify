package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class ServerRuntimeSettingsTest : FunSpec({
    test("defaults retain the complete catalog and audited server limits") {
        val settings = ServerRuntimeSettings()
        val configuration = settings.configuration(TEST_SERVER_HELLO)

        configuration.serverHello.cooldownMillis shouldBe 2_200
        configuration.serverHello.emotionCatalog shouldBe TEST_CATALOG
        configuration.selectionPolicy.enabled shouldBe true
        configuration.selectionPolicy.customEmojisEnabled shouldBe true
        configuration.audiencePolicy shouldBe ServerAudiencePolicy.DEFAULT
        settings.audienceBudgetLimits shouldBe AudienceBudgetLimits(512, 256, 32, 16, 4_096)
        settings.selectionIngressLimits shouldBe GlobalSelectionIngressLimits(512, 1_024, 512)
    }

    test("configured filters and operational limits produce one coherent runtime configuration") {
        val settings = ServerRuntimeSettings(
            enabled = false,
            customEmojisEnabled = false,
            maximumStaticCustomEmojiSize = 64,
            maximumAnimatedCustomEmojiSize = 32,
            cooldownMillis = 4_000,
            allowedEmotionIds = setOf(TEST_LOVE),
            deniedEmotionIds = setOf(TEST_HAPPY),
            audiencePolicy = ServerAudiencePolicy(32.0, 64),
            audienceBudgetLimits = AudienceBudgetLimits(128, 64, 8, 4, 512),
            selectionIngressLimits = GlobalSelectionIngressLimits(128, 256, 128),
        )
        val configuration = settings.configuration(TEST_SERVER_HELLO)

        configuration.serverHello.cooldownMillis shouldBe 4_000
        configuration.serverHello.emotionCatalog.ids shouldBe listOf(TEST_LOVE)
        configuration.selectionPolicy.enabled shouldBe false
        configuration.selectionPolicy.allowedEmotions.ids shouldBe listOf(TEST_LOVE)
        configuration.selectionPolicy.customEmojisEnabled shouldBe false
        configuration.selectionPolicy.maximumStaticCustomEmojiSize shouldBe 64
        configuration.selectionPolicy.maximumAnimatedCustomEmojiSize shouldBe 32
        configuration.audiencePolicy shouldBe ServerAudiencePolicy(32.0, 64)
    }

    test("unknown filters overlapping filters and unsafe upper limits fail closed") {
        shouldThrow<IllegalArgumentException> {
            ServerRuntimeSettings(allowedEmotionIds = setOf(TEST_UNKNOWN)).configuration(TEST_SERVER_HELLO)
        }
        shouldThrow<IllegalArgumentException> {
            ServerRuntimeSettings(
                allowedEmotionIds = setOf(TEST_HAPPY),
                deniedEmotionIds = setOf(TEST_HAPPY),
            )
        }
        shouldThrow<IllegalArgumentException> {
            ServerRuntimeSettings(
                audienceBudgetLimits = AudienceBudgetLimits(513, 256, 32, 16, 4_096),
            )
        }
        shouldThrow<IllegalArgumentException> {
            ServerRuntimeSettings(
                selectionIngressLimits = GlobalSelectionIngressLimits(513, 1_024, 512),
            )
        }
    }
})
