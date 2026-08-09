package me.whish.emotify.client.settings

import java.util.Collections
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.EmotionId

sealed interface ClientConfigurationVersion {
    data object Legacy : ClientConfigurationVersion

    data object Current : ClientConfigurationVersion

    data class Future(val value: Int) : ClientConfigurationVersion {
        init {
            require(value > ClientConfigurationSchema.CURRENT_VERSION) {
                "Future client configuration version must exceed ${ClientConfigurationSchema.CURRENT_VERSION}: $value"
            }
        }
    }
}

object ClientConfigurationSchema {
    const val LEGACY_VERSION = 0
    const val CURRENT_VERSION = 1
    const val QUICK_SLOT_COUNT = 9

    fun classify(declaredVersion: Int?): ClientConfigurationVersion {
        val version = declaredVersion ?: LEGACY_VERSION
        require(version >= LEGACY_VERSION) { "Client configuration version must not be negative: $version" }
        return when {
            version == LEGACY_VERSION -> ClientConfigurationVersion.Legacy
            version == CURRENT_VERSION -> ClientConfigurationVersion.Current
            else -> ClientConfigurationVersion.Future(version)
        }
    }
}

@ConsistentCopyVisibility
data class ClientConfigurationSnapshot private constructor(
    val settings: ClientSettingsSnapshot,
    val favorites: List<EmotionId>,
    val quickSlots: List<EmotionId?>,
) {
    private val quickSlotNumbers: Map<EmotionId, Int> = quickSlotNumbers(quickSlots)

    val schemaVersion: Int
        get() = ClientConfigurationSchema.CURRENT_VERSION

    fun withSettings(settings: ClientSettingsSnapshot): ClientConfigurationSnapshot =
        if (settings == this.settings) this else create(settings, favorites, quickSlots)

    fun withFavorites(favorites: Collection<EmotionId>): ClientConfigurationSnapshot {
        val normalizedFavorites = normalizeFavorites(favorites)
        return if (normalizedFavorites == this.favorites) this else create(settings, normalizedFavorites, quickSlots)
    }

    fun withQuickSlots(quickSlots: Collection<EmotionId?>): ClientConfigurationSnapshot {
        val updated = create(settings, favorites, quickSlots)
        return if (updated.quickSlots == this.quickSlots) this else updated
    }

    fun quickSlot(index: Int): EmotionId? {
        requireQuickSlotIndex(index)
        return quickSlots[index]
    }

    fun quickSlotNumber(emotionId: EmotionId): Int? = quickSlotNumbers[emotionId]

    fun assignQuickSlot(index: Int, emotionId: EmotionId): ClientConfigurationSnapshot {
        requireQuickSlotIndex(index)
        if (quickSlots[index] == emotionId) {
            return this
        }
        val updatedSlots = ArrayList(quickSlots)
        quickSlotNumbers[emotionId]?.let { previousSlotNumber ->
            updatedSlots[previousSlotNumber - 1] = null
        }
        updatedSlots[index] = emotionId
        return create(settings, favorites, updatedSlots)
    }

    fun clearQuickSlot(index: Int): ClientConfigurationSnapshot {
        requireQuickSlotIndex(index)
        if (quickSlots[index] == null) {
            return this
        }
        val updatedSlots = ArrayList(quickSlots)
        updatedSlots[index] = null
        return create(settings, favorites, updatedSlots)
    }

    fun retainAvailableCustomQuickSlots(availableCustomEmotionIds: Set<EmotionId>): ClientConfigurationSnapshot {
        var updatedSlots: ArrayList<EmotionId?>? = null
        quickSlots.forEachIndexed { index, emotionId ->
            if (
                emotionId != null &&
                emotionId.value.startsWith(CUSTOM_EMOTION_ID_PREFIX) &&
                emotionId !in availableCustomEmotionIds
            ) {
                val mutableSlots = updatedSlots ?: ArrayList(quickSlots).also { updatedSlots = it }
                mutableSlots[index] = null
            }
        }
        return updatedSlots?.let { slots -> create(settings, favorites, slots) } ?: this
    }

    companion object {
        fun create(
            settings: ClientSettingsSnapshot,
            favorites: Collection<EmotionId>,
            quickSlots: Collection<EmotionId?> = emptyList(),
        ): ClientConfigurationSnapshot {
            val normalizedFavorites = normalizeFavorites(favorites)
            require(quickSlots.size <= ClientConfigurationSchema.QUICK_SLOT_COUNT) {
                "Client configuration cannot contain more than ${ClientConfigurationSchema.QUICK_SLOT_COUNT} quick slots"
            }
            val normalizedQuickSlots = ArrayList<EmotionId?>(ClientConfigurationSchema.QUICK_SLOT_COUNT)
            val assigned = HashSet<EmotionId>(quickSlots.size)
            quickSlots.forEach { emotionId ->
                require(emotionId == null || assigned.add(emotionId)) {
                    "Quick slot emotion cannot be assigned more than once: $emotionId"
                }
                normalizedQuickSlots.add(emotionId)
            }
            repeat(ClientConfigurationSchema.QUICK_SLOT_COUNT - normalizedQuickSlots.size) {
                normalizedQuickSlots.add(null)
            }
            return ClientConfigurationSnapshot(
                settings,
                normalizedFavorites,
                Collections.unmodifiableList(normalizedQuickSlots),
            )
        }

        private fun normalizeFavorites(favorites: Collection<EmotionId>): List<EmotionId> = java.util.List.copyOf(
            favorites.asSequence()
                .distinct()
                .take(EmotionCatalog.MAX_SIZE)
                .toList(),
        )

        private fun quickSlotNumbers(quickSlots: List<EmotionId?>): Map<EmotionId, Int> {
            val numbers = HashMap<EmotionId, Int>(ClientConfigurationSchema.QUICK_SLOT_COUNT)
            quickSlots.forEachIndexed { index, emotionId ->
                if (emotionId != null) {
                    numbers[emotionId] = index + 1
                }
            }
            return Collections.unmodifiableMap(numbers)
        }

        private fun requireQuickSlotIndex(index: Int) {
            require(index in 0 until ClientConfigurationSchema.QUICK_SLOT_COUNT) {
                "Quick slot index must be between 0 and ${ClientConfigurationSchema.QUICK_SLOT_COUNT - 1}: $index"
            }
        }

        private const val CUSTOM_EMOTION_ID_PREFIX = "${CustomEmojiId.NAMESPACE}:"
    }
}

object ClientConfigurationMigration {
    fun fromLegacy(
        settings: ClientSettingsSnapshot,
        favorites: Collection<EmotionId>,
    ): ClientConfigurationSnapshot = ClientConfigurationSnapshot.create(settings, favorites)
}
