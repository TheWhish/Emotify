package me.whish.emotify.server.core

import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembly
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec

data class ServerCustomAssetStoreSnapshot(
    val entries: Int,
    val retainedBytes: Long,
)

class ServerCustomAssetStore(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val maximumRetainedBytes: Long = DEFAULT_MAXIMUM_RETAINED_BYTES,
) {
    private val entries = LinkedHashMap<CustomEmojiId, ServerCustomAsset>(
        maximumEntries * 4 / 3 + 1,
        0.75f,
        true,
    )
    private var retainedBytes = 0L

    init {
        require(maximumEntries > 0) { "Maximum server custom asset entries must be positive: $maximumEntries" }
        require(maximumRetainedBytes >= MAXIMUM_SINGLE_ASSET_BYTES) {
            "Server custom asset store must fit one maximum asset: $maximumRetainedBytes"
        }
    }

    fun put(
        asset: CustomEmojiAsset,
        losslessAssembly: CustomEmojiAssetAssembly?,
    ): ServerCustomAsset {
        require(asset.pixels.size <= LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE || losslessAssembly != null) {
            "A large custom asset requires a prepared lossless assembly"
        }
        require(losslessAssembly == null || losslessAssembly.asset == asset) {
            "A lossless custom asset assembly must match the stored asset"
        }
        val losslessChunks = losslessAssembly
            ?.takeIf { assembly -> assembly.asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE }
            ?.chunks
        val existing = entries[asset.id]
        if (existing != null) {
            require(existing.asset == asset) { "A custom emoji content ID cannot resolve to different asset data" }
            if (existing.losslessChunks != null || losslessChunks == null) {
                return existing
            }
        }

        val replacement = ServerCustomAsset(asset, losslessChunks)
        entries.put(asset.id, replacement)?.let { previous ->
            retainedBytes -= previous.retainedBytes
        }
        retainedBytes += replacement.retainedBytes
        evict()
        return replacement
    }

    fun find(id: CustomEmojiId): ServerCustomAsset? = entries[id]

    fun snapshot(): ServerCustomAssetStoreSnapshot = ServerCustomAssetStoreSnapshot(entries.size, retainedBytes)

    fun clear() {
        entries.clear()
        retainedBytes = 0L
    }

    private fun evict() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maximumEntries || retainedBytes > maximumRetainedBytes)) {
            retainedBytes -= iterator.next().value.retainedBytes
            iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_ENTRIES = 1_024
        const val DEFAULT_MAXIMUM_RETAINED_BYTES = 64L * 1_024 * 1_024

        private const val MAXIMUM_SINGLE_ASSET_BYTES =
            CustomEmojiAsset.MAXIMUM_RAW_BYTE_LENGTH.toLong() + CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES
        private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
    }
}

class ServerCustomAsset internal constructor(
    val asset: CustomEmojiAsset,
    losslessChunks: List<CustomEmojiAssetChunk>?,
) {
    val losslessChunks: List<CustomEmojiAssetChunk>? = losslessChunks?.let { chunks ->
        require(chunks.isNotEmpty()) { "A lossless custom asset transfer must contain chunks" }
        require(chunks.all { chunk -> chunk.customEmojiId == asset.id }) {
            "Every lossless custom asset chunk must match its content ID"
        }
        java.util.List.copyOf(chunks)
    }
    val retainedBytes: Long = asset.rawByteLength.toLong() +
        (this.losslessChunks?.sumOf { chunk -> chunk.dataLength.toLong() } ?: 0L)
}
