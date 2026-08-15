package me.whish.emotify.client

import com.mojang.blaze3d.platform.NativeImage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.custom.CustomEmojiFile
import me.whish.emotify.client.custom.CustomEmojiDiagnostic
import me.whish.emotify.client.custom.CustomEmojiDiagnosticReason
import me.whish.emotify.client.custom.CustomEmojiDirectoryEntry
import me.whish.emotify.client.custom.CustomEmojiFileScanner
import me.whish.emotify.client.custom.CustomEmojiDirectoryFingerprint
import me.whish.emotify.client.custom.CustomEmojiFileScan
import me.whish.emotify.client.custom.CustomEmojiLibraryAdmission
import me.whish.emotify.client.custom.CustomEmojiLibraryBudget
import me.whish.emotify.client.custom.CustomEmojiReferenceIndex
import me.whish.emotify.client.custom.CustomEmojiReloadCompletion
import me.whish.emotify.client.custom.CustomEmojiReloadCoordinator
import me.whish.emotify.client.custom.CustomEmojiRefreshScheduler
import me.whish.emotify.client.custom.CustomEmojiSourceCacheEntry
import me.whish.emotify.client.custom.customEmojiTextureIdsToRelease
import me.whish.emotify.client.custom.planCustomEmojiSourceReuse
import me.whish.emotify.client.custom.reconcileCustomEmojiReferences
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.client.presentation.EmotionTextureAnimation
import me.whish.emotify.client.presentation.EmotionTextureFrame
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

object CustomEmojiRegistry {
    private val logger = LoggerFactory.getLogger("Emotify/CustomEmojis")
    private val reloadCoordinator = CustomEmojiReloadCoordinator()
    private val refreshScheduler = CustomEmojiRefreshScheduler()
    private val nextRefreshCheckNanos = AtomicLong()
    private val inspectionFailureLogNanos = AtomicLong()
    private val reloadFailureLogNanos = AtomicLong()
    private val configurationFailureLogNanos = AtomicLong()
    private val cleanupFailureLogNanos = AtomicLong()

    @Volatile
    private var snapshot = CustomEmojiSnapshot.empty()

    fun directory(minecraft: Minecraft): Path = minecraft.gameDirectory.toPath().resolve(DIRECTORY_NAME)

    fun presentations(): List<EmotionPresentation> = snapshot.presentations

    fun diagnostics(): List<CustomEmojiDiagnostic> = snapshot.diagnostics

    fun find(emotionId: EmotionId): EmotionPresentation? = snapshot.byEmotionId[emotionId]

    fun contains(emotionId: EmotionId): Boolean = emotionId in snapshot.byEmotionId

    fun asset(emotionId: EmotionId): CustomEmojiAsset? = snapshot.assetByEmotionId[emotionId]

    fun descriptor(emotionId: EmotionId): CustomEmojiDescriptor? = snapshot.descriptorByEmotionId[emotionId]

    fun containsOrigin(originId: CustomEmojiId): Boolean = originId in snapshot.origins

    fun transferChunks(emotionId: EmotionId): List<CustomEmojiAssetChunk>? = snapshot.chunksByEmotionId[emotionId]

    fun resolveTexture(textureId: String): ResourceLocation? = snapshot.textureById[textureId]

    fun reload(minecraft: Minecraft, onComplete: () -> Unit = {}) {
        if (!reloadCoordinator.request(onComplete)) {
            return
        }
        scheduleReload(minecraft)
    }

    fun reloadWithResult(minecraft: Minecraft, onComplete: (Boolean) -> Unit) {
        if (!reloadCoordinator.requestWithResult(onComplete)) {
            return
        }
        scheduleReload(minecraft)
    }

    private fun scheduleReload(minecraft: Minecraft) {
        try {
            startReload(minecraft)
        } catch (failure: RuntimeException) {
            if (shouldLogFailure(reloadFailureLogNanos)) {
                logger.error("Failed to schedule custom emoji reload from {}", directory(minecraft), failure)
            }
            finishReload(minecraft, success = false)
        }
    }

    private fun startReload(minecraft: Minecraft) {
        val previous = snapshot
        CompletableFuture.supplyAsync(
            { load(directory(minecraft), previous) },
            Util.ioPool(),
        ).whenComplete { loaded, failure ->
            try {
                minecraft.execute {
                    if (failure != null) {
                        if (shouldLogFailure(reloadFailureLogNanos)) {
                            logger.error("Failed to load custom emojis from {}", directory(minecraft), failure)
                        }
                        finishReload(minecraft, success = false)
                        return@execute
                    }
                    val completedLoad = checkNotNull(loaded)
                    if (!completedLoad.stable) {
                        completedLoad.close()
                        nextRefreshCheckNanos.set(System.nanoTime() + UNSTABLE_RETRY_DELAY_NANOS)
                        finishReload(minecraft, success = false, retry = true)
                        return@execute
                    }
                    try {
                        apply(minecraft, completedLoad)
                    } catch (failure: Exception) {
                        if (shouldLogFailure(reloadFailureLogNanos)) {
                            logger.error("Failed to publish custom emojis from {}", directory(minecraft), failure)
                        }
                        finishReload(minecraft, success = false)
                        return@execute
                    }
                    if (completedLoad.references.removalSafe) {
                        reconcileCustomReferences(completedLoad.references.presentEmotionIds)
                    }
                    reloadFailureLogNanos.set(0L)
                    finishReload(minecraft, success = true)
                }
            } catch (schedulingFailure: RuntimeException) {
                loaded?.close()
                if (shouldLogFailure(reloadFailureLogNanos)) {
                    logger.error("Failed to schedule custom emoji publication from {}", directory(minecraft), schedulingFailure)
                }
                finishReload(minecraft, success = false)
            }
        }
    }

    private fun finishReload(minecraft: Minecraft, success: Boolean, retry: Boolean = false) {
        when (val completion = reloadCoordinator.complete(success, retry)) {
            CustomEmojiReloadCompletion.FollowUp -> scheduleReload(minecraft)
            is CustomEmojiReloadCompletion.Finished -> {
                completion.resultCallbacks.forEach { callback ->
                    try {
                        callback(completion.success)
                    } catch (failure: RuntimeException) {
                        logger.error("Custom emoji reload result callback failed", failure)
                    }
                }
                completion.callbacks.forEach { callback ->
                    try {
                        callback()
                    } catch (failure: RuntimeException) {
                        logger.error("Custom emoji reload callback failed", failure)
                    }
                }
            }
        }
    }

    fun refreshIfChanged(minecraft: Minecraft, onComplete: () -> Unit = {}) {
        if (reloadCoordinator.subscribe(onComplete)) {
            return
        }
        val now = System.nanoTime()
        val nextCheck = nextRefreshCheckNanos.get()
        if (now < nextCheck || !nextRefreshCheckNanos.compareAndSet(nextCheck, now + REFRESH_INTERVAL_NANOS)) {
            return
        }
        refreshScheduler.submit(
            Util.ioPool(),
            { CustomEmojiFileScanner.fingerprint(directory(minecraft)) },
        ) completion@{ fingerprint, failure ->
            if (failure != null) {
                if (shouldLogFailure(inspectionFailureLogNanos)) {
                    logger.error("Failed to inspect custom emoji directory {}", directory(minecraft), failure)
                }
                return@completion
            }
            if (reloadCoordinator.subscribe(onComplete)) {
                return@completion
            }
            inspectionFailureLogNanos.set(0L)
            if (checkNotNull(fingerprint) != snapshot.fingerprint) {
                reload(minecraft, onComplete)
            }
        }
    }

    fun openDirectory(minecraft: Minecraft, onFailure: () -> Unit = {}) {
        val opening = try {
            CompletableFuture.runAsync(
                {
                    Files.createDirectories(directory(minecraft))
                    Util.getPlatform().openPath(directory(minecraft))
                },
                Util.ioPool(),
            )
        } catch (failure: RuntimeException) {
            handleOpenDirectoryFailure(minecraft, onFailure, failure)
            return
        }
        opening.whenComplete { _, failure ->
            if (failure != null) {
                handleOpenDirectoryFailure(minecraft, onFailure, failure)
            }
        }
    }

    private fun handleOpenDirectoryFailure(minecraft: Minecraft, onFailure: () -> Unit, failure: Throwable) {
        logger.error("Failed to open custom emoji directory {}", directory(minecraft), failure)
        try {
            minecraft.execute {
                try {
                    onFailure()
                } catch (callbackFailure: RuntimeException) {
                    logger.error("Custom emoji directory failure callback failed", callbackFailure)
                }
            }
        } catch (schedulingFailure: RuntimeException) {
            failure.addSuppressed(schedulingFailure)
            logger.error("Failed to schedule custom emoji directory failure callback", schedulingFailure)
        }
    }

    private fun load(
        directory: Path,
        previous: CustomEmojiSnapshot,
    ): LoadedCustomEmojiLibrary {
        val scan = CustomEmojiFileScanner.scan(directory, previous.fileScan)
        val entries = ArrayList<LoadedCustomEmoji>(scan.accepted.size)
        val diagnostics = ArrayList<CustomEmojiDiagnostic>(scan.rejected.size)
        scan.rejected.mapTo(diagnostics, CustomEmojiDiagnostic::from)
        val decodedSourceEmotionIds = LinkedHashMap<Path, EmotionId>(scan.accepted.size)
        val unavailableSourcePaths = scan.rejected.mapTo(HashSet(scan.rejected.size)) { rejection -> rejection.path }
        val budget = CustomEmojiLibraryBudget()
        var decodeFailures = 0
        var firstDecodeFailure: Pair<Path, Throwable>? = null
        var capacityRejections = 0
        try {
            val reusePlan = planCustomEmojiSourceReuse(scan.accepted, scan.fingerprint, previous.sourceEntries)
            reusePlan.forEach { source ->
                val file = source.file
                val entry = source.resolve(
                    reuse = { cached -> LoadedCustomEmoji(file.path.normalize(), source.fingerprint, cached, null) },
                    load = { changedFile, sourceFingerprint ->
                        try {
                            load(changedFile, sourceFingerprint)
                        } catch (failure: Exception) {
                            decodeFailures++
                            unavailableSourcePaths.add(changedFile.path)
                            diagnostics += CustomEmojiDiagnostic(
                                changedFile.displayName,
                                changedFile.format,
                                if (failure is CustomEmojiFrameLimitExceededException) {
                                    CustomEmojiDiagnosticReason.TOO_MANY_FRAMES
                                } else {
                                    CustomEmojiDiagnosticReason.DECODE_FAILED
                                },
                            )
                            if (firstDecodeFailure == null) {
                                firstDecodeFailure = changedFile.path to failure
                            }
                            null
                        }
                    },
                )
                if (entry != null) {
                    decodedSourceEmotionIds[file.path] = entry.presentation.emotionId
                    when (budget.admit(entry.asset.id, entry.retainedByteLength)) {
                        CustomEmojiLibraryAdmission.ACCEPTED -> entries += entry
                        CustomEmojiLibraryAdmission.DUPLICATE -> {
                            diagnostics += CustomEmojiDiagnostic(
                                file.displayName,
                                file.format,
                                CustomEmojiDiagnosticReason.DUPLICATE,
                            )
                            entry.closeImage()
                        }
                        CustomEmojiLibraryAdmission.CAPACITY_REACHED -> {
                            capacityRejections++
                            diagnostics += CustomEmojiDiagnostic(
                                file.displayName,
                                file.format,
                                CustomEmojiDiagnosticReason.CAPACITY_REACHED,
                            )
                            entry.closeImage()
                        }
                    }
                }
            }
            firstDecodeFailure?.let { (path, failure) ->
                logger.warn("Rejected {} custom emoji files during decode; first failure: {}", decodeFailures, path, failure)
            }
            if (scan.rejected.isNotEmpty()) {
                logger.warn("Rejected {} unsupported custom emoji files in {}", scan.rejected.size, directory)
            }
            if (capacityRejections > 0) {
                logger.warn("Rejected {} custom emoji files after reaching the {} MiB local memory budget", capacityRejections, CustomEmojiLibraryBudget.DEFAULT_MAXIMUM_RETAINED_BYTES / 1_024 / 1_024)
            }
            if (scan.fingerprint.directoryLimitReached) {
                logger.warn("Custom emoji directory limit reached in {}; loading at most {} files", directory, CustomEmojiFileScanner.MAXIMUM_FILES)
            }
            val stable = CustomEmojiFileScanner.fingerprint(directory) == scan.fingerprint
            val references = reconcileCustomEmojiReferences(
                previous.sourceEmotionIds,
                decodedSourceEmotionIds,
                unavailableSourcePaths,
                scan.fingerprint.directoryLimitReached,
            )
            return LoadedCustomEmojiLibrary(entries, diagnostics, scan, stable, references)
        } catch (failure: Throwable) {
            entries.forEach(LoadedCustomEmoji::closeImage)
            throw failure
        }
    }

    private fun load(
        file: CustomEmojiFile,
        sourceFingerprint: CustomEmojiDirectoryEntry,
    ): LoadedCustomEmoji {
        require(Files.isRegularFile(file.path, LinkOption.NOFOLLOW_LINKS)) {
            "Custom emoji is no longer a regular file: ${file.path}"
        }
        val bytes = Files.newInputStream(file.path).use { input ->
            input.readNBytes(CustomEmojiFileScanner.MAXIMUM_FILE_BYTES + 1)
        }
        require(bytes.size <= CustomEmojiFileScanner.MAXIMUM_FILE_BYTES) {
            "Custom emoji exceeds the byte limit: ${file.path}"
        }
        require(CustomEmojiFileScanner.matchesExpectedImage(file, bytes)) {
            "Custom emoji dimensions or format changed during load: ${file.path}"
        }
        val decoded = CustomEmojiImageDecoder.decode(file, bytes)
        try {
            require(decoded.frames.all { image -> image.width == file.sourceSize && image.height == file.sourceSize }) {
                "Decoded custom emoji frame dimensions changed during load: ${file.path}"
            }
            val frames = decoded.frames.mapIndexed { index, image ->
                CustomEmojiFrame(image.toCustomEmojiPixels(), decoded.durationMillisAt(index))
            }
            val asset = CustomEmojiAsset.create(frames)
            val descriptor = CustomEmojiEmbeddedDescriptor.read(file.format, bytes)
                ?: CustomEmojiDescriptor.create(file.displayName, asset.id)
            val transferChunks = if (asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
                java.util.List.copyOf(CustomEmojiAssetChunker.split(asset))
            } else {
                emptyList()
            }
            val emotionId = asset.id.emotionId
            val texture = ResourceLocation.fromNamespaceAndPath(CUSTOM_NAMESPACE, asset.id.hexValue())
            val atlas = decoded.toAtlas()
            try {
                val regions = asset.frames.mapIndexed { index, frame ->
                    EmotionSpriteRegion(
                        index * frame.pixels.width,
                        0,
                        frame.pixels.width,
                        frame.pixels.height,
                        atlas.width,
                        atlas.height,
                    )
                }
                val textureAnimation = if (asset.isAnimated) {
                    EmotionTextureAnimation(
                        regions.mapIndexed { index, region ->
                            EmotionTextureFrame(region, asset.frames[index].durationMillis)
                        },
                    )
                } else {
                    null
                }
                val presentation = EmotionPresentation(
                    emotionId,
                    texture.toString(),
                    "",
                    CUSTOM_CATEGORY,
                    "",
                    0,
                    regions.first(),
                    descriptor.displayName,
                    textureAnimation,
                )
                return LoadedCustomEmoji(
                    file.path.normalize(),
                    sourceFingerprint,
                    CachedCustomEmoji(presentation, texture, asset, descriptor, transferChunks),
                    atlas,
                )
            } catch (failure: Throwable) {
                atlas.close()
                throw failure
            }
        } finally {
            decoded.close()
        }
    }

    private fun DecodedCustomEmoji.toAtlas(): NativeImage {
        val frameWidth = frames.first().width
        val frameHeight = frames.first().height
        val atlas = NativeImage(frameWidth * frameCount, frameHeight, true)
        try {
            frames.forEachIndexed { frameIndex, frame ->
                repeat(frameWidth * frameHeight) { pixelIndex ->
                    atlas.setPixelRGBA(
                        frameIndex * frameWidth + pixelIndex % frameWidth,
                        pixelIndex / frameWidth,
                        frame.getPixelRGBA(pixelIndex % frameWidth, pixelIndex / frameWidth),
                    )
                }
            }
            return atlas
        } catch (failure: Throwable) {
            atlas.close()
            throw failure
        }
    }

    private fun NativeImage.toCustomEmojiPixels(): CustomEmojiPixels = CustomEmojiPixels.of(
        width,
        IntArray(width * height) { index ->
            getPixelRGBA(index % width, index / width)
        },
    )

    private fun apply(minecraft: Minecraft, loaded: LoadedCustomEmojiLibrary) {
        val previous = snapshot
        val pendingImages = java.util.Collections.newSetFromMap(IdentityHashMap<NativeImage, Boolean>())
        loaded.entries.mapNotNullTo(pendingImages, LoadedCustomEmoji::image)
        val registeredTextures = ArrayList<ResourceLocation>(loaded.entries.size)
        val publication = try {
            val retainedEntries = LinkedHashMap<EmotionId, LoadedCustomEmoji>(loaded.entries.size)
            loaded.entries.forEach { entry ->
                val replaced = retainedEntries.putIfAbsent(entry.presentation.emotionId, entry)
                if (replaced != null) {
                    entry.closeImage()
                    entry.image?.let(pendingImages::remove)
                }
            }
            val nextTextureIds = retainedEntries.values.mapTo(HashSet()) { entry -> entry.presentation.textureId }
            retainedEntries.values.forEach { entry ->
                val image = entry.image ?: return@forEach
                val unchanged = previous.assetByEmotionId.containsKey(entry.presentation.emotionId) &&
                    previous.textureById[entry.presentation.textureId] == entry.texture
                if (unchanged) {
                    entry.closeImage()
                    pendingImages.remove(image)
                    return@forEach
                }
                val texture = try {
                    DynamicTexture(image)
                } catch (failure: Throwable) {
                    closePendingImage(image, pendingImages, failure)
                    throw failure
                }
                registeredTextures += entry.texture
                try {
                    minecraft.textureManager.register(entry.texture, texture)
                } catch (failure: Throwable) {
                    registeredTextures.removeAt(registeredTextures.lastIndex)
                    try {
                        texture.close()
                        pendingImages.remove(image)
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
                pendingImages.remove(image)
            }
            CustomEmojiPublication(
                CustomEmojiSnapshot.from(
                    retainedEntries.values,
                    loaded.diagnostics,
                    loaded.fileScan,
                    loaded.references.sourceEmotionIds,
                ),
                nextTextureIds,
            )
        } catch (failure: Throwable) {
            rollbackPublication(minecraft, pendingImages, registeredTextures, failure)
        }
        snapshot = publication.snapshot
        cleanupPublishedSnapshot(minecraft, previous, publication.textureIds)
    }

    private fun reconcileCustomReferences(availableCustomEmotionIds: Set<EmotionId>) {
        try {
            val configured = ClientConfigurationSnapshot.create(
                EmotifyClientConfig.settings(),
                EmotifyClientConfig.loadFavorites(),
                EmotifyClientConfig.loadQuickSlots(),
            )
            val reconciled = configured.retainAvailableCustomReferences(availableCustomEmotionIds)
            if (reconciled.favorites != configured.favorites) {
                EmotifyClientConfig.saveFavorites(reconciled.favorites)
            }
            if (reconciled.quickSlots != configured.quickSlots) {
                EmotifyClientConfig.saveQuickSlots(reconciled.quickSlots)
            }
            configurationFailureLogNanos.set(0L)
        } catch (failure: Exception) {
            if (shouldLogFailure(configurationFailureLogNanos)) {
                logger.error("Failed to reconcile custom emoji favorites and quick slots", failure)
            }
        }
    }

    private fun rollbackPublication(
        minecraft: Minecraft,
        pendingImages: Set<NativeImage>,
        registeredTextures: List<ResourceLocation>,
        failure: Throwable,
    ): Nothing {
        pendingImages.forEach { image ->
            try {
                image.close()
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        registeredTextures.forEach { texture ->
            try {
                minecraft.textureManager.release(texture)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    }

    private fun closePendingImage(
        image: NativeImage,
        pendingImages: MutableSet<NativeImage>,
        failure: Throwable,
    ) {
        try {
            image.close()
            pendingImages.remove(image)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun cleanupPublishedSnapshot(
        minecraft: Minecraft,
        previous: CustomEmojiSnapshot,
        nextTextureIds: Set<String>,
    ) {
        var firstFailure: Exception? = null
        try {
            EmotionBillboardRenderTypes.retainLocalCustomTextures(nextTextureIds)
        } catch (failure: Exception) {
            firstFailure = failure
        }
        customEmojiTextureIdsToRelease(previous.textureById.keys, nextTextureIds).forEach { textureId ->
            val texture = checkNotNull(previous.textureById[textureId])
            try {
                minecraft.textureManager.release(texture)
            } catch (failure: Exception) {
                val existingFailure = firstFailure
                if (existingFailure == null) {
                    firstFailure = failure
                } else {
                    existingFailure.addSuppressed(failure)
                }
            }
        }
        val cleanupFailure = firstFailure
        if (cleanupFailure == null) {
            cleanupFailureLogNanos.set(0L)
        } else if (shouldLogFailure(cleanupFailureLogNanos)) {
            logger.error("Failed to clean up obsolete custom emoji textures", cleanupFailure)
        }
    }

    private data class CustomEmojiPublication(
        val snapshot: CustomEmojiSnapshot,
        val textureIds: Set<String>,
    )

    private const val DIRECTORY_NAME = "emoji"
    private const val CUSTOM_NAMESPACE = "emotify_custom"
    private const val CUSTOM_CATEGORY = "custom"
    private const val REFRESH_INTERVAL_NANOS = 500_000_000L
    private const val UNSTABLE_RETRY_DELAY_NANOS = 2_000_000_000L
    private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
    private const val FAILURE_LOG_INTERVAL_NANOS = 30_000_000_000L

    private fun shouldLogFailure(gate: AtomicLong): Boolean {
        val now = System.nanoTime()
        while (true) {
            val previous = gate.get()
            if (previous != 0L && now - previous < FAILURE_LOG_INTERVAL_NANOS) {
                return false
            }
            if (gate.compareAndSet(previous, now)) {
                return true
            }
        }
    }
}

object EmotionPresentationRegistry {
    fun find(emotionId: EmotionId): EmotionPresentation? =
        CustomEmojiRegistry.find(emotionId)
            ?: RemoteCustomEmojiRegistry.find(emotionId)
            ?: EmotionPresentationCatalog.find(emotionId)

    fun contains(emotionId: EmotionId): Boolean = find(emotionId) != null
}

private data class CustomEmojiSnapshot(
    val presentations: List<EmotionPresentation>,
    val diagnostics: List<CustomEmojiDiagnostic>,
    val byEmotionId: Map<EmotionId, EmotionPresentation>,
    val textureById: Map<String, ResourceLocation>,
    val assetByEmotionId: Map<EmotionId, CustomEmojiAsset>,
    val descriptorByEmotionId: Map<EmotionId, CustomEmojiDescriptor>,
    val origins: Set<CustomEmojiId>,
    val chunksByEmotionId: Map<EmotionId, List<CustomEmojiAssetChunk>>,
    val fileScan: CustomEmojiFileScan,
    val sourceEntries: Map<Path, CustomEmojiSourceCacheEntry<CachedCustomEmoji>>,
    val sourceEmotionIds: Map<Path, EmotionId>,
) {
    val fingerprint: CustomEmojiDirectoryFingerprint
        get() = fileScan.fingerprint

    companion object {
        fun empty(): CustomEmojiSnapshot = CustomEmojiSnapshot(
            emptyList(),
            emptyList(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptySet(),
            emptyMap(),
            CustomEmojiFileScan.EMPTY,
            emptyMap(),
            emptyMap(),
        )

        fun from(
            entries: Collection<LoadedCustomEmoji>,
            diagnostics: Collection<CustomEmojiDiagnostic>,
            fileScan: CustomEmojiFileScan,
            sourceEmotionIds: Map<Path, EmotionId>,
        ): CustomEmojiSnapshot {
            val presentations = java.util.List.copyOf(entries.map(LoadedCustomEmoji::presentation))
            return CustomEmojiSnapshot(
                presentations,
                java.util.List.copyOf(diagnostics),
                java.util.Map.copyOf(presentations.associateBy(EmotionPresentation::emotionId)),
                java.util.Map.copyOf(entries.associate { entry -> entry.presentation.textureId to entry.texture }),
                java.util.Map.copyOf(entries.associate { entry -> entry.presentation.emotionId to entry.asset }),
                java.util.Map.copyOf(entries.associate { entry -> entry.presentation.emotionId to entry.descriptor }),
                java.util.Set.copyOf(entries.map { entry -> entry.descriptor.originId }),
                java.util.Map.copyOf(entries.associate { entry -> entry.presentation.emotionId to entry.transferChunks }),
                fileScan,
                java.util.Map.copyOf(entries.associate { entry ->
                    entry.sourcePath to CustomEmojiSourceCacheEntry(entry.sourceFingerprint, entry.cached)
                }),
                java.util.Map.copyOf(sourceEmotionIds),
            )
        }
    }
}

private data class LoadedCustomEmoji(
    val sourcePath: Path,
    val sourceFingerprint: CustomEmojiDirectoryEntry,
    val cached: CachedCustomEmoji,
    val image: NativeImage?,
) {
    val presentation: EmotionPresentation
        get() = cached.presentation

    val texture: ResourceLocation
        get() = cached.texture

    val asset: CustomEmojiAsset
        get() = cached.asset

    val descriptor: CustomEmojiDescriptor
        get() = cached.descriptor

    val transferChunks: List<CustomEmojiAssetChunk>
        get() = cached.transferChunks

    val retainedByteLength: Long
        get() = cached.retainedByteLength

    fun closeImage() {
        image?.close()
    }
}

private data class CachedCustomEmoji(
    val presentation: EmotionPresentation,
    val texture: ResourceLocation,
    val asset: CustomEmojiAsset,
    val descriptor: CustomEmojiDescriptor,
    val transferChunks: List<CustomEmojiAssetChunk>,
) {
    val retainedByteLength: Long = asset.rawByteLength.toLong() * 2L +
        transferChunks.sumOf(CustomEmojiAssetChunk::dataLength)
}

private class LoadedCustomEmojiLibrary(
    val entries: List<LoadedCustomEmoji>,
    val diagnostics: List<CustomEmojiDiagnostic>,
    val fileScan: CustomEmojiFileScan,
    val stable: Boolean,
    val references: CustomEmojiReferenceIndex,
) {
    fun close() {
        entries.forEach(LoadedCustomEmoji::closeImage)
    }
}
