package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId

@Suppress("unused")
class ClientEmotionPlayCoordinatorTest : FunSpec({
    val sourceUuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val customEmojiId = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64))).id

    fun play(sequence: Long) = EmotionPlay(
        RuntimeEntityId.of(7),
        sourceUuid,
        EventSequence.of(sequence),
        EmotionId.of("emotify:happy"),
    )

    fun customPlay(sequence: Long) = CustomEmotionPlay(
        RuntimeEntityId.of(7),
        sourceUuid,
        EventSequence.of(sequence),
        customEmojiId,
    )

    fun evaluate(
        coordinator: ClientEmotionPlayCoordinator,
        sequence: Long,
        localSource: Boolean,
        settings: ClientSettingsSnapshot,
    ): ClientEmotionPlayDisposition = coordinator.evaluate(
        12,
        BuiltInEmotionCatalog.catalog,
        play(sequence),
        7,
        sourceUuid,
        true,
        localSource,
        "RemotePlayer",
        settings,
    )

    fun evaluateCustom(
        coordinator: ClientEmotionPlayCoordinator,
        sequence: Long,
        localSource: Boolean,
        settings: ClientSettingsSnapshot,
    ): ClientEmotionPlayDisposition = coordinator.evaluateCustom(
        12,
        customPlay(sequence),
        7,
        sourceUuid,
        true,
        localSource,
        "RemotePlayer",
        settings,
    )

    test("hidden valid play advances replay protection before presentation filtering") {
        val coordinator = ClientEmotionPlayCoordinator()
        coordinator.begin(12)
        val settings = ClientSettingsSnapshot.defaults().withShowOtherPlayers(false)

        evaluate(coordinator, 50, false, settings) shouldBe ClientEmotionPlayDisposition.HIDDEN
        evaluate(coordinator, 49, false, settings) shouldBe ClientEmotionPlayDisposition.REJECTED
    }

    test("local play bypasses both global visibility and ignored identity") {
        val coordinator = ClientEmotionPlayCoordinator()
        coordinator.begin(12)
        val settings = ClientSettingsSnapshot.defaults()
            .withShowOtherPlayers(false)
            .withPlayerIgnored(sourceUuid, "RemotePlayer", true)

        evaluate(coordinator, 1, true, settings) shouldBe ClientEmotionPlayDisposition.VISIBLE
    }

    test("custom visibility hides remote custom play without hiding built-in play") {
        val coordinator = ClientEmotionPlayCoordinator()
        coordinator.begin(12)
        val settings = ClientSettingsSnapshot.defaults().withShowCustomEmotions(false)

        evaluateCustom(coordinator, 1, false, settings) shouldBe ClientEmotionPlayDisposition.HIDDEN
        evaluate(coordinator, 2, false, settings) shouldBe ClientEmotionPlayDisposition.VISIBLE
        evaluateCustom(coordinator, 3, true, settings) shouldBe ClientEmotionPlayDisposition.VISIBLE
    }

    test("invalid identity is rejected before client visibility is considered") {
        val coordinator = ClientEmotionPlayCoordinator()
        coordinator.begin(12)

        coordinator.evaluate(
            12,
            BuiltInEmotionCatalog.catalog,
            play(50),
            8,
            sourceUuid,
            true,
            false,
            "RemotePlayer",
            ClientSettingsSnapshot.defaults(),
        ) shouldBe ClientEmotionPlayDisposition.REJECTED

        evaluate(coordinator, 1, false, ClientSettingsSnapshot.defaults()) shouldBe
            ClientEmotionPlayDisposition.VISIBLE
    }
})
