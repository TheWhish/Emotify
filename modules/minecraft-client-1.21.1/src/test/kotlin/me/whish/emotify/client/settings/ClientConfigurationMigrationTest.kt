package me.whish.emotify.client.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class ClientConfigurationMigrationTest : FunSpec({
    val first = EmotionId.of("emotify:smile")
    val second = EmotionId.of("emotify:heart")

    test("legacy schema migrates settings and favorites into schema one") {
        val settings = ClientSettingsSnapshot.defaults()

        val migrated = ClientConfigurationMigration.fromLegacy(
            settings,
            listOf(first, second, first),
        )

        migrated.schemaVersion shouldBe ClientConfigurationSchema.CURRENT_VERSION
        migrated.settings shouldBe settings
        migrated.favorites.shouldContainExactly(first, second)
        migrated.quickSlots.shouldContainExactly(List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { null })
    }

    test("schema classification distinguishes legacy current and future documents") {
        ClientConfigurationSchema.classify(null) shouldBe ClientConfigurationVersion.Legacy
        ClientConfigurationSchema.classify(0) shouldBe ClientConfigurationVersion.Legacy
        ClientConfigurationSchema.classify(1) shouldBe ClientConfigurationVersion.Current
        ClientConfigurationSchema.classify(2) shouldBe ClientConfigurationVersion.Future(2)

        shouldThrow<IllegalArgumentException> {
            ClientConfigurationSchema.classify(-1)
        }
    }

    test("schema one snapshot has exactly nine unique quick slots independent from favorites") {
        val snapshot = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.defaults(),
            listOf(first),
            listOf(first, null, second),
        )

        snapshot.quickSlots.shouldContainExactly(
            first,
            null,
            second,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        shouldThrow<IllegalArgumentException> {
            ClientConfigurationSnapshot.create(
                ClientSettingsSnapshot.defaults(),
                listOf(first),
                List(ClientConfigurationSchema.QUICK_SLOT_COUNT + 1) { null },
            )
        }
        shouldThrow<IllegalArgumentException> {
            ClientConfigurationSnapshot.create(
                ClientSettingsSnapshot.defaults(),
                listOf(first),
                listOf(first, first),
            )
        }
        snapshot.quickSlotNumber(second) shouldBe 3
    }

    test("immutable snapshot updates preserve invariants across settings favorites and slots") {
        val original = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.defaults(),
            listOf(first, second),
            listOf(first, second),
        )
        val updatedSettings = ClientSettingsSnapshot.defaults().withReducedMotion(true)

        val settingsUpdate = original.withSettings(updatedSettings)
        val favoritesUpdate = settingsUpdate.withFavorites(listOf(second))
        val slotsUpdate = favoritesUpdate.withQuickSlots(listOf(null, second))

        original.settings.reducedMotion shouldBe false
        original.quickSlots.take(2).shouldContainExactly(first, second)
        settingsUpdate.settings shouldBe updatedSettings
        favoritesUpdate.favorites.shouldContainExactly(second)
        favoritesUpdate.quickSlots.take(2).shouldContainExactly(first, second)
        slotsUpdate.quickSlots.take(2).shouldContainExactly(null, second)
    }

    test("assigning a quick slot atomically moves an existing emotion") {
        val snapshot = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.defaults(),
            listOf(first, second),
            listOf(first, null, second),
        )

        val moved = snapshot.assignQuickSlot(7, first)

        moved.quickSlots.shouldContainExactly(
            null,
            null,
            second,
            null,
            null,
            null,
            null,
            first,
            null,
        )
        moved.quickSlot(7) shouldBe first
        moved.quickSlotNumber(first) shouldBe 8
        moved.quickSlotNumber(second) shouldBe 3
        snapshot.quickSlotNumber(first) shouldBe 1
    }

    test("clearing a quick slot is immutable and idempotent") {
        val snapshot = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.defaults(),
            listOf(first),
            listOf(first),
        )

        val cleared = snapshot.clearQuickSlot(0)

        cleared.quickSlots.shouldContainExactly(List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { null })
        cleared.quickSlotNumber(first) shouldBe null
        cleared.clearQuickSlot(0) shouldBe cleared
        snapshot.quickSlot(0) shouldBe first
    }

    test("quick slot mutations validate bounds and favorite membership") {
        val snapshot = ClientConfigurationSnapshot.create(
            ClientSettingsSnapshot.defaults(),
            listOf(first),
        )

        shouldThrow<IllegalArgumentException> { snapshot.quickSlot(-1) }
        shouldThrow<IllegalArgumentException> { snapshot.quickSlot(ClientConfigurationSchema.QUICK_SLOT_COUNT) }
        snapshot.assignQuickSlot(0, second).quickSlot(0) shouldBe second
        shouldThrow<IllegalArgumentException> { snapshot.assignQuickSlot(-1, first) }
        shouldThrow<IllegalArgumentException> {
            snapshot.clearQuickSlot(ClientConfigurationSchema.QUICK_SLOT_COUNT)
        }
    }
})
