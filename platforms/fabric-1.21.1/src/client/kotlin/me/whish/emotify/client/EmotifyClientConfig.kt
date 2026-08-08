package me.whish.emotify.client

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.state.FailureLogGate
import me.whish.emotify.client.state.SerializedSnapshotStore
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.config.FabricClientConfigCodec
import me.whish.emotify.fabric.config.FabricClientConfigSnapshot
import net.fabricmc.loader.api.FabricLoader

object EmotifyClientConfig {
    private val configPath = FabricLoader.getInstance().configDir.resolve("emotify-client.properties")
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

    fun initialize() {
        val initial = snapshots.load()
        currentSettings = initial.settings
        if (!Files.exists(configPath)) {
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
        snapshots.update { current -> current.copy(settings = settings) }
        currentSettings = settings
    }

    fun loadFavorites(): List<EmotionId> = snapshots.load().favorites

    fun saveFavorites(ids: Collection<EmotionId>) {
        val favorites = normalizedFavorites(ids)
        snapshots.update { current -> current.copy(favorites = favorites) }
    }

    fun flush() {
        if (
            !snapshots.flush(CONFIG_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            failureLogGate.tryAcquire(System.nanoTime())
        ) {
            EmotifyFabric.LOGGER.warn("Emotify client config was not fully persisted before shutdown")
        }
    }

    private fun loadSnapshot(): FabricClientConfigSnapshot {
        if (!Files.exists(configPath)) {
            return defaultSnapshot()
        }
        return try {
            FabricClientConfigCodec.decode(readBoundedUtf8(), defaultSnapshot())
        } catch (error: Exception) {
            EmotifyFabric.LOGGER.error("Failed to load Emotify client config from {}", configPath, error)
            defaultSnapshot()
        }
    }

    private fun persistSnapshot(snapshot: FabricClientConfigSnapshot) {
        val parent = checkNotNull(configPath.parent) { "Emotify config path has no parent: $configPath" }
        Files.createDirectories(parent)
        val temporary = parent.resolve("${configPath.fileName}.tmp")
        Files.writeString(temporary, FabricClientConfigCodec.encode(snapshot), StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                configPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readBoundedUtf8(): String {
        val bytes = Files.newInputStream(configPath).use { input ->
            input.readNBytes(MAXIMUM_CONFIG_BYTES + 1)
        }
        require(bytes.size <= MAXIMUM_CONFIG_BYTES) { "Emotify client config exceeds $MAXIMUM_CONFIG_BYTES bytes" }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun defaultSnapshot(): FabricClientConfigSnapshot = FabricClientConfigSnapshot(
        settings = ClientSettingsSnapshot.defaults(),
        favorites = defaultFavorites(),
    )

    private fun defaultFavorites(): List<EmotionId> =
        normalizedFavorites(BuiltInEmotionManifest.defaultFavoriteIds)

    private fun normalizedFavorites(ids: Collection<EmotionId>): List<EmotionId> = java.util.List.copyOf(
        ids.asSequence()
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList(),
    )

    private fun logPersistenceFailure(error: Throwable) {
        if (failureLogGate.tryAcquire(System.nanoTime())) {
            EmotifyFabric.LOGGER.error("Failed to persist Emotify client config", error)
        }
    }

    private const val MAXIMUM_CONFIG_BYTES = 65_536
    private const val CONFIG_FLUSH_TIMEOUT_SECONDS = 2L
}
