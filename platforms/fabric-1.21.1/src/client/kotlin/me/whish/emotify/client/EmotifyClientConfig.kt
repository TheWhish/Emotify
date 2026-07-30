package me.whish.emotify.client

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.state.FailureLogGate
import me.whish.emotify.client.state.SerializedSnapshotStore
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
        Thread(task, "Emotify Favorites Persistence").apply {
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
    private var currentAnimationMotion = AnimationMotion.FULL

    fun initialize() {
        val initial = snapshots.load()
        currentAnimationMotion = if (initial.reducedMotion) {
            AnimationMotion.REDUCED
        } else {
            AnimationMotion.FULL
        }
        if (!Files.exists(configPath)) {
            snapshots.submit(initial)
        }
    }

    fun animationMotion(): AnimationMotion = currentAnimationMotion

    fun loadFavorites(): List<EmotionId> = snapshots.load().favorites

    fun saveFavorites(ids: Collection<EmotionId>) {
        val current = snapshots.load()
        snapshots.submit(current.copy(favorites = normalizedFavorites(ids)))
    }

    private fun loadSnapshot(): FabricClientConfigSnapshot {
        if (!Files.exists(configPath)) {
            return defaultSnapshot()
        }
        return try {
            FabricClientConfigCodec.decode(readBoundedUtf8(configPath), defaultFavorites())
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

    private fun readBoundedUtf8(path: Path): String {
        val size = Files.size(path)
        require(size <= MAXIMUM_CONFIG_BYTES) { "Emotify client config exceeds $MAXIMUM_CONFIG_BYTES bytes" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size <= MAXIMUM_CONFIG_BYTES) { "Emotify client config exceeds $MAXIMUM_CONFIG_BYTES bytes" }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun defaultSnapshot(): FabricClientConfigSnapshot = FabricClientConfigSnapshot(
        reducedMotion = false,
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
            EmotifyFabric.LOGGER.error("Failed to persist Emotify client favorites", error)
        }
    }

    private const val MAXIMUM_CONFIG_BYTES = 65_536
}
