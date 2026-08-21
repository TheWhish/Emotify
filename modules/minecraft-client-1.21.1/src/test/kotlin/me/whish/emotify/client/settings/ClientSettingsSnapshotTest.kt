package me.whish.emotify.client.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

@Suppress("unused")
class ClientSettingsSnapshotTest : FunSpec({
    val licensedUuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val offlineUuid = UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100")

    test("custom emotion visibility defaults on and updates immutably") {
        val defaults = ClientSettingsSnapshot.defaults()
        val hidden = defaults.withShowCustomEmotions(false)

        defaults.showCustomEmotions shouldBe true
        hidden.showCustomEmotions shouldBe false
        defaults.showCustomEmotions shouldBe true
        hidden.withShowCustomEmotions(true) shouldBe defaults
    }

    test("hotbar feedback visibility defaults on and updates immutably") {
        val defaults = ClientSettingsSnapshot.defaults()
        val disabled = defaults.withShowHotbarFeedback(false)

        defaults.showHotbarFeedback shouldBe true
        disabled.showHotbarFeedback shouldBe false
        disabled.withShowHotbarFeedback(true) shouldBe defaults
    }

    test("ignored identity follows a stable UUID across a rename") {
        val settings = ClientSettingsSnapshot.create(
            true,
            false,
            80,
            listOf(IgnoredPlayerIdentity.of(licensedUuid, "OldName")),
        )

        settings.isPlayerIgnored(licensedUuid, "NewName") shouldBe true
    }

    test("normalized name fallback follows an offline player across UUID changes") {
        val settings = ClientSettingsSnapshot.create(
            true,
            false,
            80,
            listOf(IgnoredPlayerIdentity.of(licensedUuid, "OfflinePlayer")),
        )

        settings.isPlayerIgnored(offlineUuid, "offlineplayer") shouldBe true
        settings.isPlayerIgnored(offlineUuid, "AnotherPlayer") shouldBe false
        settings.isPlayerIgnored(offlineUuid, "я".repeat(33)) shouldBe false
    }

    test("updating an offline identity replaces stale UUID and spelling") {
        val original = ClientSettingsSnapshot.create(
            true,
            false,
            100,
            listOf(IgnoredPlayerIdentity.of(licensedUuid, "OfflinePlayer")),
        )

        val updated = original.withPlayerIgnored(offlineUuid, "offlineplayer", true)

        updated.ignoredPlayers.shouldContainExactly(
            IgnoredPlayerIdentity.of(offlineUuid, "offlineplayer"),
        )
    }

    test("unignoring removes either UUID or normalized name match") {
        val original = ClientSettingsSnapshot.create(
            true,
            false,
            100,
            listOf(IgnoredPlayerIdentity.of(licensedUuid, "OfflinePlayer")),
        )

        original.withPlayerIgnored(offlineUuid, "OFFLINEPLAYER", false).ignoredPlayers shouldBe emptyList()
    }

    test("settings own an immutable normalized ignored player list") {
        val identities = mutableListOf(
            IgnoredPlayerIdentity.of(licensedUuid, "FirstName"),
            IgnoredPlayerIdentity.of(licensedUuid, "LatestName"),
            IgnoredPlayerIdentity.of(offlineUuid, "latestname"),
        )

        val settings = ClientSettingsSnapshot.create(true, true, 25, identities)
        identities.clear()

        settings.ignoredPlayers.shouldContainExactly(
            IgnoredPlayerIdentity.of(offlineUuid, "latestname"),
        )
    }

    test("normalization removes every transitive UUID and name conflict") {
        val firstUuid = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val secondUuid = UUID.fromString("20000000-0000-0000-0000-000000000002")

        val settings = ClientSettingsSnapshot.create(
            true,
            false,
            100,
            listOf(
                IgnoredPlayerIdentity.of(firstUuid, "SecondName"),
                IgnoredPlayerIdentity.of(secondUuid, "FirstName"),
                IgnoredPlayerIdentity.of(secondUuid, "SecondName"),
            ),
        )

        settings.ignoredPlayers.shouldContainExactly(
            IgnoredPlayerIdentity.of(secondUuid, "SecondName"),
        )
    }

    test("ignored player capacity is bounded without evicting saved identities") {
        val identities = List(ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS) { index ->
            IgnoredPlayerIdentity.of(UUID(0, index.toLong() + 1), "Player$index")
        }
        val settings = ClientSettingsSnapshot.create(true, false, 100, identities)

        settings.withPlayerIgnored(UUID(1, 1), "Overflow", true) shouldBe settings
        settings.ignoredPlayers.size shouldBe ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS
    }

    test("invalid names and sound volumes fail fast") {
        shouldThrow<IllegalArgumentException> {
            IgnoredPlayerIdentity.of(licensedUuid, "\u0000")
        }
        shouldThrow<IllegalArgumentException> {
            IgnoredPlayerIdentity.of(licensedUuid, "я".repeat(33))
        }
        shouldThrow<IllegalArgumentException> {
            ClientSettingsSnapshot.create(true, false, 101, emptyList())
        }
    }
})
