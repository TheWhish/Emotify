package me.whish.emotify.client

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.settings.ClientConfigurationFileIO
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.state.FailureLogGate
import me.whish.emotify.client.state.SerializedSnapshotStore
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.config.FabricClientConfigCodec
import me.whish.emotify.fabric.config.FabricClientConfigDecodeResult
import net.fabricmc.loader.api.FabricLoader

object EmotifyClientConfig {
    private val configPath = FabricLoader.getInstance().configDir.resolve("emotify-client.properties")
    private val backupPath = configPath.resolveSibling("${configPath.fileName}.pre-v2.bak")
    private val failureLogGate = FailureLogGate(TimeUnit.SECONDS.toNanos(30))
    private val persistenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Emotify Client Config Persistence").apply {
            isDaemon = true
        }
    }
    private val snapshots = SerializedSnapshotStore(
        loader = ::loadSnapshot,
        executor = persistenceExecutor,
        sink = ::persistSnapshot,
        onFailure = ::logPersistenceFailure,
    )
    @Volatile
    private var currentSettings = ClientSettingsSnapshot.defaults()
    @Volatile
    private var writesAllowed = true
    private var rewriteAfterLoad = false

    fun initialize() {
        val configExists = Files.exists(configPath)
        val initial = snapshots.load()
        currentSettings = initial.settings
        if (writesAllowed && (!configExists || rewriteAfterLoad)) {
            snapshots.submit(initial)
        }
    }

    fun animationMotion(): AnimationMotion = if (currentSettings.reducedMotion) {
        AnimationMotion.REDUCED
    } else {
        AnimationMotion.FULL
    }

    fun settings(): ClientSettingsSnapshot = currentSettings

    fun saveSettings(settings: ClientSettingsSnapshot) {
        updateSnapshot { current -> current.withSettings(settings) }
        currentSettings = settings
    }

    fun loadFavorites(): List<EmotionId> = snapshots.load().favorites

    fun saveFavorites(ids: Collection<EmotionId>) {
        updateSnapshot { current -> current.withFavorites(ids) }
    }

    fun loadQuickSlots(): List<EmotionId?> = snapshots.load().quickSlots

    fun saveQuickSlots(ids: Collection<EmotionId?>) {
        updateSnapshot { current -> current.withQuickSlots(ids) }
    }

    fun isCustomCopyHintDismissed(): Boolean = snapshots.load().customCopyHintDismissed

    fun dismissCustomCopyHint() {
        updateSnapshot { current -> current.withCustomCopyHintDismissed(true) }
    }

    fun flush() {
        if (
            !snapshots.flush(CONFIG_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            failureLogGate.tryAcquire(System.nanoTime())
        ) {
            EmotifyFabric.LOGGER.warn("Emotify client config was not fully persisted before shutdown")
        }
    }

    private fun loadSnapshot(): ClientConfigurationSnapshot {
        if (!Files.exists(configPath)) {
            return defaultSnapshot()
        }
        return try {
            when (val decoded = FabricClientConfigCodec.decode(readBoundedUtf8(), defaultSnapshot())) {
                is FabricClientConfigDecodeResult.Ready -> {
                    if (decoded.migrationRequired) {
                        prepareLegacyMigration()
                    }
                    decoded.snapshot
                }
                is FabricClientConfigDecodeResult.Future -> {
                    writesAllowed = false
                    EmotifyFabric.LOGGER.warn(
                        "Emotify client config schema {} is newer than supported schema {}; using session defaults without changing the file",
                        decoded.schemaVersion,
                        me.whish.emotify.client.settings.ClientConfigurationSchema.CURRENT_VERSION,
                    )
                    defaultSnapshot()
                }
            }
        } catch (error: Exception) {
            writesAllowed = false
            EmotifyFabric.LOGGER.error(
                "Failed to load Emotify client config from {}; using session defaults without changing the file",
                configPath,
                error,
            )
            defaultSnapshot()
        }
    }

    private fun prepareLegacyMigration() {
        try {
            ClientConfigurationFileIO.createBackupIfAbsent(configPath, backupPath, MAXIMUM_CONFIG_BYTES)
            rewriteAfterLoad = true
        } catch (error: Exception) {
            writesAllowed = false
            EmotifyFabric.LOGGER.error(
                "Failed to back up legacy Emotify client config {}; migration is disabled for this session",
                configPath,
                error,
            )
        }
    }

    private fun persistSnapshot(snapshot: ClientConfigurationSnapshot) {
        check(writesAllowed) { "Emotify client configuration is read-only for this session" }
        ClientConfigurationFileIO.writeUtf8Atomically(configPath, FabricClientConfigCodec.encode(snapshot))
    }

    private fun readBoundedUtf8(): String =
        ClientConfigurationFileIO.readUtf8(configPath, MAXIMUM_CONFIG_BYTES)

    private fun defaultSnapshot(): ClientConfigurationSnapshot = ClientConfigurationSnapshot.create(
        settings = ClientSettingsSnapshot.defaults(),
        favorites = BuiltInEmotionManifest.defaultFavoriteIds,
    )

    private fun updateSnapshot(transform: (ClientConfigurationSnapshot) -> ClientConfigurationSnapshot) {
        if (writesAllowed) {
            snapshots.update(transform)
        } else {
            snapshots.updateInMemory(transform)
        }
    }

    private fun logPersistenceFailure(error: Throwable) {
        if (failureLogGate.tryAcquire(System.nanoTime())) {
            EmotifyFabric.LOGGER.error("Failed to persist Emotify client config", error)
        }
    }

    private const val MAXIMUM_CONFIG_BYTES = 65_536
    private const val CONFIG_FLUSH_TIMEOUT_SECONDS = 2L
}
