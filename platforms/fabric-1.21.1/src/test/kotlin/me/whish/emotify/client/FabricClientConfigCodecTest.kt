package me.whish.emotify.fabric.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FabricClientConfigCodecTest : FunSpec({
    val first = EmotionId.of("emotify:smile")
    val second = EmotionId.of("emotify:heart")
    val ignored = IgnoredPlayerIdentity.of(
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        "OfflinePlayer",
    )
    val defaults = ClientConfigurationSnapshot.create(ClientSettingsSnapshot.defaults(), listOf(first, second))

    test("config round trip preserves every setting and ordered favorites") {
        val custom = CustomEmojiId(1L, 2L, 3L).emotionId
        val snapshot = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.create(
                false,
                true,
                35,
                listOf(ignored),
                showCustomEmotions = false,
            ),
            listOf(first, second),
            listOf(second, custom, first),
        )

        val encoded = FabricClientConfigCodec.encode(snapshot)
        val decoded = FabricClientConfigCodec.decode(
            encoded,
            defaults,
        )

        encoded.shouldStartWith("configVersion=${ClientConfigurationSchema.CURRENT_VERSION}\n")
        decoded shouldBe FabricClientConfigDecodeResult.Ready(snapshot, migrationRequired = false)
    }

    test("legacy config retains defaults for newly introduced settings") {
        val decoded = FabricClientConfigCodec.decode(
            "reducedMotion=true\nfavorites=${second.value}\n",
            defaults,
        ) as FabricClientConfigDecodeResult.Ready

        decoded.migrationRequired shouldBe true
        decoded.snapshot.settings.showOtherPlayers shouldBe true
        decoded.snapshot.settings.showCustomEmotions shouldBe true
        decoded.snapshot.settings.reducedMotion shouldBe true
        decoded.snapshot.settings.soundVolumePercent shouldBe 100
        decoded.snapshot.settings.ignoredPlayers shouldBe emptyList()
        decoded.snapshot.favorites.shouldContainExactly(second)
        decoded.snapshot.quickSlots.shouldContainExactly(List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { null })
    }

    test("duplicate favorites are normalized without reordering") {
        val decoded = FabricClientConfigCodec.decode(
            "favorites=${first.value},${second.value},${first.value}\n",
            defaults,
        ) as FabricClientConfigDecodeResult.Ready

        decoded.snapshot.favorites shouldContainExactly listOf(first, second)
    }

    test("future schema remains opaque and is never interpreted as the current document") {
        FabricClientConfigCodec.decode(
            "configVersion=2\nfutureOption=true\n",
            defaults,
        ) shouldBe FabricClientConfigDecodeResult.Future(2)
    }

    test("malformed values and duplicate keys are rejected") {
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("reducedMotion=yes\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("favorites=invalid\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("favorites=${first.value}\nfavorites=${second.value}\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("configVersion=1\nfutureOption=true\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("soundVolumePercent=101\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("ignoredPlayers=invalid\n", defaults)
        }
        val duplicateName = IgnoredPlayerIdentity.of(UUID(1, 1), "offlineplayer")
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode(
                "ignoredPlayers=${IgnoredPlayerIdentityCodec.encode(ignored)}," +
                    "${IgnoredPlayerIdentityCodec.encode(duplicateName)}\n",
                defaults,
            )
        }
        val oversizedList = List(ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS + 1) { index ->
            IgnoredPlayerIdentityCodec.encode(
                IgnoredPlayerIdentity.of(UUID(0, index.toLong() + 1), "Player$index"),
            )
        }.joinToString(",")
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("ignoredPlayers=$oversizedList\n", defaults)
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode(
                "configVersion=1\nfavorites=${first.value},${second.value}\n" +
                    "quickSlots=${first.value},${first.value},,,,,,,\n",
                defaults,
            )
        }
    }
})
