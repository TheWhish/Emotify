package me.whish.emotify.client

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.whish.emotify.Emotify
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.client.state.FailureLogGate
import me.whish.emotify.client.state.SerializedSnapshotStore
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import net.neoforged.neoforge.common.ModConfigSpec

object EmotifyClientConfig {
    private val defaultFavoriteValues = BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value)
    private val defaultIgnoredIdentity = IgnoredPlayerIdentityCodec.encode(
        IgnoredPlayerIdentity.of(
            UUID(0L, 0L),
            "Emotify",
        ),
    )
    private val builder = ModConfigSpec.Builder()
    private val showOtherPlayersEmotions = builder.define("showOtherPlayersEmotions", true)
    private val showCustomEmotions = builder.define("showCustomEmotions", true)
    private val reducedMotion = builder.define("reducedMotion", false)
    private val soundVolumePercent = builder.defineInRange(
        "soundVolumePercent",
        ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
        ClientSettingsSnapshot.MINIMUM_SOUND_VOLUME_PERCENT,
        ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
    )
    private val ignoredPlayers = builder.defineList(
        listOf("ignoredPlayers"),
        { emptyList<String>() },
        { defaultIgnoredIdentity },
        { value -> IgnoredPlayerIdentityCodec.decodeOrNull(value) != null },
        ModConfigSpec.Range.of(0, ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS),
    )
    private val favoriteIds = builder.defineListAllowEmpty(
        "favorites",
        { defaultFavoriteValues },
        { defaultFavoriteValues.first() },
        { value -> value is String && EmotionId.parse(value) != null },
    )

    val spec: ModConfigSpec = builder.build()
    private val failureLogGate = FailureLogGate(TimeUnit.SECONDS.toNanos(30))
    private val persistenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Emotify Client Config Persistence").apply {
            isDaemon = true
        }
    }
    private val snapshots = SerializedSnapshotStore(
        loader = ::loadConfiguredSnapshot,
        executor = persistenceExecutor,
        sink = ::persistSnapshot,
        onFailure = ::logPersistenceFailure,
    )
    @Volatile
    private var currentSettings: ClientSettingsSnapshot? = null

    fun animationMotion(): AnimationMotion = if (settings().reducedMotion) {
        AnimationMotion.REDUCED
    } else {
        AnimationMotion.FULL
    }

    fun settings(): ClientSettingsSnapshot {
        val current = currentSettings
        if (current != null) {
            return current
        }
        return snapshots.load().settings.also { loaded -> currentSettings = loaded }
    }

    fun saveSettings(settings: ClientSettingsSnapshot) {
        snapshots.update { current -> current.copy(settings = settings) }
        currentSettings = settings
    }

    @Suppress("unused")
    fun loadFavorites(): List<EmotionId> = snapshots.load().favorites

    @Suppress("unused")
    fun saveFavorites(ids: Collection<EmotionId>) {
        val favorites = normalizedFavorites(ids)
        snapshots.update { current -> current.copy(favorites = favorites) }
    }

    fun flush() {
        if (
            !snapshots.flush(CONFIG_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            failureLogGate.tryAcquire(System.nanoTime())
        ) {
            Emotify.LOGGER.warn("Emotify client config was not fully persisted before shutdown")
        }
    }

    private fun loadConfiguredSnapshot(): NeoForgeClientConfigSnapshot = NeoForgeClientConfigSnapshot(
        settings = ClientSettingsSnapshot.create(
            showOtherPlayersEmotions.get(),
            reducedMotion.get(),
            soundVolumePercent.get(),
            loadIgnoredPlayers(),
            showCustomEmotions.get(),
        ),
        favorites = loadFavoriteIds(),
    )

    private fun loadIgnoredPlayers(): List<IgnoredPlayerIdentity> {
        val configured = ignoredPlayers.get()
        if (configured.size > ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS) {
            Emotify.LOGGER.warn(
                "Ignoring oversized Emotify ignored-player list: {} entries",
                configured.size,
            )
            return emptyList()
        }
        return configured.mapNotNull { encoded -> IgnoredPlayerIdentityCodec.decodeOrNull(encoded) }
    }

    private fun loadFavoriteIds(): List<EmotionId> {
        val configured = favoriteIds.get()
        if (configured.size > EmotionCatalog.MAX_SIZE) {
            Emotify.LOGGER.warn("Ignoring oversized Emotify favorites list: {} entries", configured.size)
            return normalizedFavorites(BuiltInEmotionManifest.defaultFavoriteIds)
        }
        return normalizedFavorites(configured.mapNotNull(EmotionId::parse))
    }

    private fun persistSnapshot(snapshot: NeoForgeClientConfigSnapshot) {
        showOtherPlayersEmotions.set(snapshot.settings.showOtherPlayers)
        showCustomEmotions.set(snapshot.settings.showCustomEmotions)
        reducedMotion.set(snapshot.settings.reducedMotion)
        soundVolumePercent.set(snapshot.settings.soundVolumePercent)
        ignoredPlayers.set(snapshot.settings.ignoredPlayers.map(IgnoredPlayerIdentityCodec::encode))
        favoriteIds.set(snapshot.favorites.map(EmotionId::value))
        spec.save()
    }

    private fun normalizedFavorites(ids: Collection<EmotionId>): List<EmotionId> = java.util.List.copyOf(
        ids.asSequence()
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList(),
    )

    private fun logPersistenceFailure(error: Throwable) {
        if (failureLogGate.tryAcquire(System.nanoTime())) {
            Emotify.LOGGER.error("Failed to persist Emotify client config", error)
        }
    }

    private const val CONFIG_FLUSH_TIMEOUT_SECONDS = 2L
}

private data class NeoForgeClientConfigSnapshot(
    val settings: ClientSettingsSnapshot,
    val favorites: List<EmotionId>,
)
