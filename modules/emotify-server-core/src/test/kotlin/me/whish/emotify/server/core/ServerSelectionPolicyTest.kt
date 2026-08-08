package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionCatalog

@Suppress("unused")
class ServerSelectionPolicyTest : FunSpec({
    test("allowed catalog must remain a subset of advertised catalog") {
        ServerSelectionPolicy(true, TEST_CATALOG, EmotionCatalog.of(listOf(TEST_HAPPY)))

        shouldThrow<IllegalArgumentException> {
            ServerSelectionPolicy(true, TEST_CATALOG, EmotionCatalog.of(listOf(TEST_UNKNOWN)))
        }
    }

    test("policy values are structurally immutable snapshots") {
        val policy = TEST_ENABLED_POLICY
        val disabled = policy.copy(enabled = false)

        policy.enabled shouldBe true
        disabled.enabled shouldBe false
        disabled.catalog shouldBe policy.catalog
    }

    test("custom emoji availability is an independent immutable policy value") {
        val disabled = TEST_ENABLED_POLICY.copy(customEmojisEnabled = false)

        TEST_ENABLED_POLICY.customEmojisEnabled shouldBe true
        disabled.enabled shouldBe true
        disabled.customEmojisEnabled shouldBe false
        disabled.allowedEmotions shouldBe TEST_ENABLED_POLICY.allowedEmotions
    }
})
