package me.whish.emotify.client.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class ClientEmotionVisibilityTest : FunSpec({
    val localUuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val remoteUuid = UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100")

    test("local emotion bypasses global and identity filters") {
        val settings = ClientSettingsSnapshot.create(
            false,
            false,
            100,
            listOf(IgnoredPlayerIdentity.of(localUuid, "LocalPlayer")),
        )

        ClientEmotionVisibility.allowsBuiltIn(true, localUuid, "LocalPlayer", settings) shouldBe true
        ClientEmotionVisibility.allowsCustom(true, localUuid, "LocalPlayer", settings) shouldBe true
    }

    test("remote emotion requires global visibility and non-ignored identity") {
        val hiddenGlobally = ClientSettingsSnapshot.defaults().withShowOtherPlayers(false)
        val hiddenIndividually = ClientSettingsSnapshot.defaults()
            .withPlayerIgnored(remoteUuid, "RemotePlayer", true)

        ClientEmotionVisibility.allowsBuiltIn(false, remoteUuid, "RemotePlayer", hiddenGlobally) shouldBe false
        ClientEmotionVisibility.allowsBuiltIn(false, remoteUuid, "RemotePlayer", hiddenIndividually) shouldBe false
        ClientEmotionVisibility.allowsBuiltIn(
            false,
            remoteUuid,
            "RemotePlayer",
            ClientSettingsSnapshot.defaults(),
        ) shouldBe true
    }

    test("custom visibility only filters remote custom emotions") {
        val settings = ClientSettingsSnapshot.defaults().withShowCustomEmotions(false)
        val customEmotionId = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64))).id.emotionId

        ClientEmotionVisibility.allowsBuiltIn(false, remoteUuid, "RemotePlayer", settings) shouldBe true
        ClientEmotionVisibility.allowsCustom(false, remoteUuid, "RemotePlayer", settings) shouldBe false
        ClientEmotionVisibility.allowsCustom(true, localUuid, "LocalPlayer", settings) shouldBe true
        ClientEmotionVisibility.allowsActive(
            false,
            remoteUuid,
            "RemotePlayer",
            EmotionId.of("emotify:happy"),
            settings,
        ) shouldBe true
        ClientEmotionVisibility.allowsActive(
            false,
            remoteUuid,
            "RemotePlayer",
            customEmotionId,
            settings,
        ) shouldBe false
    }
})
