package me.whish.emotify.client

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.Emotify
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.settings.ClientConfigurationFileIO
import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.client.settings.ClientConfigurationVersion
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import me.whish.emotify.client.settings.IgnoredPlayerIdentityCodec
import me.whish.emotify.client.state.FailureLogGate
import me.whish.emotify.client.state.SerializedSnapshotStore
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.ModConfigSpec

object EmotifyClientConfig {
    private val defaultFavoriteValues = BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value)
    private val defaultQuickSlotValues = List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { "" }
    private val defaultIgnoredIdentity = IgnoredPlayerIdentityCodec.encode(
        IgnoredPlayerIdentity.of(
            UUID(0L, 0L),
            "Emotify",
        ),
    )
    private val builder = ModConfigSpec.Builder()
    private val configVersion = builder.defineInRange(
        "configVersion",
        ClientConfigurationSchema.CURRENT_VERSION,
        ClientConfigurationSchema.LEGACY_VERSION,
        Int.MAX_VALUE,
    )
    private val showOtherPlayersEmotions = builder.define("showOtherPlayersEmotions", true)
    private val showCustomEmotions = builder.define("showCustomEmotions", true)
    private val showHotbarFeedback = builder.define("showHotbarFeedback", true)
    private val customCopyHintDismissed = builder.define("customCopyHintDismissed", false)
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
    private val quickSlotIds = builder.defineList(
        listOf("quickSlots"),
        { defaultQuickSlotValues },
        { "" },
        { value -> value is String && (value.isEmpty() || EmotionId.parse(value) != null) },
        ModConfigSpec.Range.of(
            ClientConfigurationSchema.QUICK_SLOT_COUNT,
            ClientConfigurationSchema.QUICK_SLOT_COUNT,
        ),
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
    private val migrationPending = AtomicBoolean()
    @Volatile
    private var currentSettings: ClientSettingsSnapshot? = null
    @Volatile
    private var writesAllowed = true
    @Volatile
    private var configRegistered = true

    fun prepareForRegistration(): Boolean {
        val configPath = FMLPaths.CONFIGDIR.get().resolve(CLIENT_CONFIG_FILE_NAME)
        if (!Files.exists(configPath)) {
            configRegistered = true
            return true
        }
        return try {
            when (
                val version = NeoForgeClientConfigVersionCodec.inspect(
                    ClientConfigurationFileIO.readUtf8(configPath, MAXIMUM_CONFIG_BYTES),
                )
            ) {
                ClientConfigurationVersion.Legacy,
                ClientConfigurationVersion.SchemaOne -> {
                    val backupPath = configPath.resolveSibling("${configPath.fileName}.pre-v2.bak")
                    ClientConfigurationFileIO.createBackupIfAbsent(
                        configPath,
                        backupPath,
                        MAXIMUM_CONFIG_BYTES,
                    )
                    migrationPending.set(true)
                    configRegistered = true
                    true
                }
                ClientConfigurationVersion.Current -> {
                    configRegistered = true
                    true
                }
                is ClientConfigurationVersion.Future -> {
                    writesAllowed = false
                    configRegistered = false
                    Emotify.LOGGER.warn(
                        "Emotify client config schema {} is newer than supported schema {}; using session defaults without registering or changing the file",
                        version.value,
                        ClientConfigurationSchema.CURRENT_VERSION,
                    )
                    false
                }
            }
        } catch (error: Exception) {
            writesAllowed = false
            configRegistered = false
            Emotify.LOGGER.error(
                "Failed to inspect or back up {}; using session defaults without registering or changing the file",
                configPath,
                error,
            )
            false
        }
    }

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
        return configuredSnapshot().settings.also { loaded -> currentSettings = loaded }
    }

    fun saveSettings(settings: ClientSettingsSnapshot) {
        updateSnapshot { current -> current.withSettings(settings) }
        currentSettings = settings
    }

    @Suppress("unused")
    fun loadFavorites(): List<EmotionId> = configuredSnapshot().favorites

    @Suppress("unused")
    fun saveFavorites(ids: Collection<EmotionId>) {
        updateSnapshot { current -> current.withFavorites(ids) }
    }

    @Suppress("unused")
    fun loadQuickSlots(): List<EmotionId?> = configuredSnapshot().quickSlots

    @Suppress("unused")
    fun saveQuickSlots(ids: Collection<EmotionId?>) {
        updateSnapshot { current -> current.withQuickSlots(ids) }
    }

    fun isCustomCopyHintDismissed(): Boolean = configuredSnapshot().customCopyHintDismissed

    @Suppress("unused")
    fun dismissCustomCopyHint() {
        updateSnapshot { current -> current.withCustomCopyHintDismissed(true) }
    }

    fun flush() {
        if (
            !snapshots.flush(CONFIG_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            failureLogGate.tryAcquire(System.nanoTime())
        ) {
            Emotify.LOGGER.warn("Emotify client config was not fully persisted before shutdown")
        }
    }

    private fun configuredSnapshot(): ClientConfigurationSnapshot {
        val snapshot = snapshots.load()
        if (writesAllowed && migrationPending.compareAndSet(true, false)) {
            snapshots.submit(snapshot)
        }
        return snapshot
    }

    private fun loadConfiguredSnapshot(): ClientConfigurationSnapshot {
        if (!configRegistered) {
            return defaultSnapshot()
        }
        val favorites = loadFavoriteIds()
        return ClientConfigurationSnapshot.create(
            settings = ClientSettingsSnapshot.create(
                showOtherPlayersEmotions.get(),
                reducedMotion.get(),
                soundVolumePercent.get(),
                loadIgnoredPlayers(),
                showCustomEmotions.get(),
                showHotbarFeedback.get(),
            ),
            favorites = favorites,
            quickSlots = decodeNeoForgeQuickSlotIds(quickSlotIds.get()),
            customCopyHintDismissed = customCopyHintDismissed.get(),
        )
    }

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

    private fun persistSnapshot(snapshot: ClientConfigurationSnapshot) {
        check(writesAllowed && configRegistered) { "Emotify client configuration is read-only for this session" }
        configVersion.set(snapshot.schemaVersion)
        showOtherPlayersEmotions.set(snapshot.settings.showOtherPlayers)
        showCustomEmotions.set(snapshot.settings.showCustomEmotions)
        showHotbarFeedback.set(snapshot.settings.showHotbarFeedback)
        reducedMotion.set(snapshot.settings.reducedMotion)
        soundVolumePercent.set(snapshot.settings.soundVolumePercent)
        ignoredPlayers.set(snapshot.settings.ignoredPlayers.map(IgnoredPlayerIdentityCodec::encode))
        favoriteIds.set(snapshot.favorites.map(EmotionId::value))
        quickSlotIds.set(snapshot.quickSlots.map { emotionId -> emotionId?.value.orEmpty() })
        customCopyHintDismissed.set(snapshot.customCopyHintDismissed)
        spec.save()
    }

    private fun defaultSnapshot(): ClientConfigurationSnapshot = ClientConfigurationSnapshot.create(
        ClientSettingsSnapshot.defaults(),
        BuiltInEmotionManifest.defaultFavoriteIds,
    )

    private fun normalizedFavorites(ids: Collection<EmotionId>): List<EmotionId> = java.util.List.copyOf(
        ids.asSequence()
            .distinct()
            .take(EmotionCatalog.MAX_SIZE)
            .toList(),
    )

    private fun updateSnapshot(transform: (ClientConfigurationSnapshot) -> ClientConfigurationSnapshot) {
        if (writesAllowed && configRegistered) {
            snapshots.update(transform)
        } else {
            snapshots.updateInMemory(transform)
        }
    }

    private fun logPersistenceFailure(error: Throwable) {
        if (failureLogGate.tryAcquire(System.nanoTime())) {
            Emotify.LOGGER.error("Failed to persist Emotify client config", error)
        }
    }

    private const val CLIENT_CONFIG_FILE_NAME = "${Emotify.ID}-client.toml"
    private const val MAXIMUM_CONFIG_BYTES = 65_536
    private const val CONFIG_FLUSH_TIMEOUT_SECONDS = 2L
}

internal fun decodeNeoForgeQuickSlotIds(configured: List<String>): List<EmotionId?> {
    if (configured.size != ClientConfigurationSchema.QUICK_SLOT_COUNT) {
        Emotify.LOGGER.warn("Ignoring Emotify quick slots with invalid size: {}", configured.size)
        return List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { null }
    }
    val assigned = HashSet<EmotionId>(ClientConfigurationSchema.QUICK_SLOT_COUNT)
    return configured.map { value ->
        EmotionId.parse(value)?.takeIf(assigned::add)
    }
}
