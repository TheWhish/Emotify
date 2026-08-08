package me.whish.emotify.fabric.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FabricClientConfigCodecTest : FunSpec({
    val first = EmotionId.of("emotify:smile")
    val second = EmotionId.of("emotify:heart")
    val ignored = IgnoredPlayerIdentity.of(
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        "OfflinePlayer",
    )
    val defaults = FabricClientConfigSnapshot(ClientSettingsSnapshot.defaults(), listOf(first, second))

    test("config round trip preserves every setting and ordered favorites") {
        val snapshot = FabricClientConfigSnapshot(
            ClientSettingsSnapshot.create(
                false,
                true,
                35,
                listOf(ignored),
                showCustomEmotions = false,
            ),
            listOf(first, second),
        )

        FabricClientConfigCodec.decode(
            FabricClientConfigCodec.encode(snapshot),
            defaults,
        ) shouldBe snapshot
    }

    test("legacy config retains defaults for newly introduced settings") {
        val decoded = FabricClientConfigCodec.decode(
            "reducedMotion=true\nfavorites=${second.value}\n",
            defaults,
        )

        decoded.settings.showOtherPlayers shouldBe true
        decoded.settings.showCustomEmotions shouldBe true
        decoded.settings.reducedMotion shouldBe true
        decoded.settings.soundVolumePercent shouldBe 100
        decoded.settings.ignoredPlayers shouldBe emptyList()
        decoded.favorites.shouldContainExactly(second)
    }

    test("duplicate favorites are normalized without reordering") {
        val decoded = FabricClientConfigCodec.decode(
            "favorites=${first.value},${second.value},${first.value}\n",
            defaults,
        )

        decoded.favorites shouldContainExactly listOf(first, second)
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
            FabricClientConfigCodec.decode("futureOption=true\n", defaults)
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
    }
})
