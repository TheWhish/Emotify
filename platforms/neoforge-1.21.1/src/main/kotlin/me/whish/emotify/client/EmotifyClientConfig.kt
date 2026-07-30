package me.whish.emotify.client

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.whish.emotify.Emotify
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import net.neoforged.neoforge.common.ModConfigSpec

object EmotifyClientConfig {
    private val defaultFavoriteValues = BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value)
    private val builder = ModConfigSpec.Builder()
    private val reducedMotion = builder.define("reducedMotion", false)
    private val favoriteIds = builder.defineListAllowEmpty(
        "favorites",
        defaultFavoriteValues,
        { defaultFavoriteValues.first() },
        { value ->
            value is String && EmotionId.parse(value) != null
        },
    )

    val spec: ModConfigSpec = builder.build()
    private val failureLogGate = FailureLogGate(TimeUnit.SECONDS.toNanos(30))
    private val persistenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Emotify Favorites Persistence").apply {
            isDaemon = true
        }
    }
    private val favoriteSnapshots = SerializedSnapshotStore(
        loader = ::loadConfiguredFavorites,
        executor = persistenceExecutor,
        sink = ::persistFavorites,
        onFailure = ::logPersistenceFailure,
    )

    fun animationMotion(): AnimationMotion = if (reducedMotion.get()) {
        AnimationMotion.REDUCED
    } else {
        AnimationMotion.FULL
    }

    fun loadFavorites(): List<EmotionId> = favoriteSnapshots.load()

    fun saveFavorites(ids: Collection<EmotionId>) {
        favoriteSnapshots.submit(normalizedFavorites(ids))
    }

    private fun loadConfiguredFavorites(): List<EmotionId> = normalizedFavorites(
        favoriteIds.get()
            .asSequence()
            .mapNotNull(EmotionId::parse)
            .toList(),
    )

    private fun persistFavorites(ids: List<EmotionId>) {
        favoriteIds.set(
            ids.map(EmotionId::value),
        )
        favoriteIds.save()
    }

    private fun normalizedFavorites(ids: Collection<EmotionId>): List<EmotionId> = java.util.List.copyOf(
        ids.asSequence()
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList(),
    )

    private fun logPersistenceFailure(error: Throwable) {
        if (failureLogGate.tryAcquire(System.nanoTime())) {
            Emotify.LOGGER.error("Failed to persist Emotify client favorites", error)
        }
    }
}
