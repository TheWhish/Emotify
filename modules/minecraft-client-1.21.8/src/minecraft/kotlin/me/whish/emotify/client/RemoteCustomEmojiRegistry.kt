package me.whish.emotify.client

import com.mojang.blaze3d.platform.NativeImage
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionTextureAnimation
import me.whish.emotify.client.presentation.EmotionTextureFrame
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.RemoteCustomEmojiRetention
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

object RemoteCustomEmojiRegistry {
    private var activeConnectionId = 0L
    private val lookup = RemoteCustomEmojiLookup<RemoteCustomEmoji, ResourceLocation>()
    private val retention = RemoteCustomEmojiRetention()

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        clear()
        activeConnectionId = connectionId
    }

    fun register(connectionId: Long, asset: CustomEmojiAsset): Boolean {
        if (connectionId != activeConnectionId) {
            return false
        }
        if (lookup.contains(asset.id)) {
            return true
        }
        return register(connectionId, prepare(asset))
    }

    fun prepare(asset: CustomEmojiAsset): PreparedRemoteCustomEmoji {
        val image = NativeImage(asset.pixels.width * asset.frames.size, asset.pixels.height, true)
        val texture: ResourceLocation
        val presentation: EmotionPresentation
        try {
            writePixels(image, asset)
            texture = ResourceLocation.fromNamespaceAndPath(
                CustomEmojiId.NAMESPACE,
                "remote/${asset.id.hexValue()}",
            )
            presentation = presentation(asset, texture)
        } catch (failure: Throwable) {
            image.close()
            throw failure
        }
        return PreparedRemoteCustomEmoji(asset, image, presentation, texture)
    }

    fun discard(prepared: PreparedRemoteCustomEmoji) {
        prepared.image.close()
    }

    fun register(connectionId: Long, prepared: PreparedRemoteCustomEmoji): Boolean {
        val asset = prepared.asset
        if (connectionId != activeConnectionId) {
            discard(prepared)
            return false
        }
        if (lookup.contains(asset.id)) {
            discard(prepared)
            return true
        }
        val dynamicTexture = try {
            DynamicTexture({ "Emotify remote custom emoji ${asset.id.hexValue()}" }, prepared.image)
        } catch (failure: Throwable) {
            prepared.image.close()
            throw failure
        }
        try {
            Minecraft.getInstance().textureManager.register(prepared.texture, dynamicTexture)
        } catch (failure: Throwable) {
            try {
                dynamicTexture.close()
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        val remote = RemoteCustomEmoji(asset, prepared.presentation, prepared.texture)
        try {
            lookup.add(asset.id, remote.presentation.textureId, remote, remote.texture)
        } catch (failure: Throwable) {
            try {
                Minecraft.getInstance().textureManager.release(prepared.texture)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        retention.retain(asset.id, asset.rawByteLength).forEach { evictedId ->
            lookup.remove(evictedId)?.let(::release)
        }
        return true
    }

    fun find(emotionId: EmotionId): EmotionPresentation? = lookup.find(emotionId)?.presentation

    fun asset(emotionId: EmotionId): CustomEmojiAsset? = lookup.find(emotionId)?.asset

    fun contains(emotionId: EmotionId): Boolean = find(emotionId) != null

    fun resolveTexture(textureId: String): ResourceLocation? = lookup.resolveTexture(textureId)

    fun disconnect(connectionId: Long) {
        if (connectionId != activeConnectionId) {
            return
        }
        clear()
        activeConnectionId = 0L
    }

    private fun clear() {
        lookup.clear().forEach(::release)
        retention.clear()
    }

    private fun release(entry: RemoteCustomEmoji) {
        EmotionBillboardRenderTypes.releaseCustomTexture(entry.presentation.textureId)
        Minecraft.getInstance().textureManager.release(entry.texture)
    }

    @Suppress("DEPRECATION")
    private fun writePixels(image: NativeImage, asset: CustomEmojiAsset) {
        asset.frames.forEachIndexed { frameIndex, frame ->
            repeat(frame.pixels.pixelCount) { index ->
                image.setPixelABGR(
                    frameIndex * frame.pixels.width + index % frame.pixels.width,
                    index / frame.pixels.width,
                    frame.pixels.colorAt(index),
                )
            }
        }
    }

    private fun presentation(asset: CustomEmojiAsset, texture: ResourceLocation): EmotionPresentation {
        val textureWidth = asset.pixels.width * asset.frames.size
        val regions = asset.frames.mapIndexed { index, frame ->
            EmotionSpriteRegion(
                index * frame.pixels.width,
                0,
                frame.pixels.width,
                frame.pixels.height,
                textureWidth,
                frame.pixels.height,
            )
        }
        val animation = if (asset.isAnimated) {
            EmotionTextureAnimation(
                regions.mapIndexed { index, region ->
                    EmotionTextureFrame(region, asset.frames[index].durationMillis)
                },
            )
        } else {
            null
        }
        return EmotionPresentation(
            asset.id.emotionId,
            texture.toString(),
            "",
            "custom",
            "",
            0,
            regions.first(),
            "Custom emoji",
            animation,
        )
    }

    private data class RemoteCustomEmoji(
        val asset: CustomEmojiAsset,
        val presentation: EmotionPresentation,
        val texture: ResourceLocation,
    )

    class PreparedRemoteCustomEmoji internal constructor(
        val asset: CustomEmojiAsset,
        internal val image: NativeImage,
        internal val presentation: EmotionPresentation,
        internal val texture: ResourceLocation,
    )
}

class RemoteCustomEmojiLookup<T : Any, R : Any> {
    private val entries = HashMap<CustomEmojiId, IndexedEntry<T>>()
    private val entriesByEmotionId = HashMap<EmotionId, T>()
    private val texturesByTextureId = HashMap<String, R>()

    fun contains(id: CustomEmojiId): Boolean = id in entries

    fun add(id: CustomEmojiId, textureId: String, value: T, texture: R) {
        check(id !in entries) { "Remote custom emoji is already indexed: $id" }
        check(id.emotionId !in entriesByEmotionId) { "Remote custom emotion is already indexed: ${id.emotionId}" }
        check(textureId !in texturesByTextureId) { "Remote custom texture is already indexed: $textureId" }
        entries[id] = IndexedEntry(textureId, value)
        entriesByEmotionId[id.emotionId] = value
        texturesByTextureId[textureId] = texture
    }

    fun find(emotionId: EmotionId): T? = entriesByEmotionId[emotionId]

    fun resolveTexture(textureId: String): R? = texturesByTextureId[textureId]

    fun remove(id: CustomEmojiId): T? {
        val removed = entries.remove(id) ?: return null
        entriesByEmotionId.remove(id.emotionId)
        texturesByTextureId.remove(removed.textureId)
        return removed.value
    }

    fun clear(): List<T> {
        val removed = entries.values.map { entry -> entry.value }
        entries.clear()
        entriesByEmotionId.clear()
        texturesByTextureId.clear()
        return removed
    }

    private data class IndexedEntry<T : Any>(
        val textureId: String,
        val value: T,
    )
}
