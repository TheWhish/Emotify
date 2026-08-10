package me.whish.emotify.paper

import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.paper.network.PaperProtocolV1Bridge
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.ProtocolV1Limits

internal class PaperCustomAssetChunkPayloadCache(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val maximumRetainedBytes: Int = DEFAULT_MAXIMUM_RETAINED_BYTES,
    private val encoder: (CustomEmojiAssetChunk) -> ByteArray = PaperProtocolV1Bridge::encodeCustomAssetChunk,
) {
    private val monitor = Any()
    private val entries = LinkedHashMap<CustomEmojiId, Entry>(maximumEntries * 4 / 3 + 1, 0.75f, true)
    private var retainedBytes = 0

    init {
        require(maximumEntries > 0) { "Maximum Paper custom asset payload count must be positive: $maximumEntries" }
        require(maximumRetainedBytes > 0) {
            "Maximum Paper custom asset payload bytes must be positive: $maximumRetainedBytes"
        }
    }

    fun payloads(
        customEmojiId: CustomEmojiId,
        chunks: List<CustomEmojiAssetChunk>,
    ): List<ByteArray> {
        validate(customEmojiId, chunks)
        synchronized(monitor) {
            entries[customEmojiId]?.let { entry -> return entry.payloads }
        }

        val payloads = java.util.List.copyOf(chunks.map(encoder))
        val payloadBytes = payloads.sumOf { payload -> payload.size.toLong() }
        require(payloads.all { payload -> payload.size in 1..ProtocolV1Limits.CUSTOM_ASSET_CHUNK_BODY_BYTES }) {
            "Encoded Paper custom asset chunk body is outside Protocol 1 bounds"
        }
        require(payloadBytes <= Int.MAX_VALUE) { "Encoded Paper custom asset payload bytes exceed integer bounds" }
        val entry = Entry(payloads, payloadBytes.toInt())

        return synchronized(monitor) {
            entries[customEmojiId]?.payloads ?: retain(customEmojiId, entry)
        }
    }

    fun clear() = synchronized(monitor) {
        entries.clear()
        retainedBytes = 0
    }

    private fun retain(customEmojiId: CustomEmojiId, entry: Entry): List<ByteArray> {
        if (entry.bytes > maximumRetainedBytes) {
            return entry.payloads
        }
        val iterator = entries.entries.iterator()
        while (
            iterator.hasNext() &&
            (entries.size >= maximumEntries || retainedBytes > maximumRetainedBytes - entry.bytes)
        ) {
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
        entries[customEmojiId] = entry
        retainedBytes += entry.bytes
        return entry.payloads
    }

    private fun validate(
        customEmojiId: CustomEmojiId,
        chunks: List<CustomEmojiAssetChunk>,
    ) {
        require(chunks.isNotEmpty()) { "Paper custom asset payload requires at least one chunk" }
        val first = chunks.first()
        require(first.customEmojiId == customEmojiId) { "Paper custom asset payload content ID does not match its cache key" }
        require(chunks.size == first.count) { "Paper custom asset payload chunk count is incomplete" }
        require(chunks.sumOf(CustomEmojiAssetChunk::dataLength) == first.totalBytes) {
            "Paper custom asset payload byte count is incomplete"
        }
        chunks.forEachIndexed { index, chunk ->
            require(
                chunk.customEmojiId == customEmojiId &&
                    chunk.totalBytes == first.totalBytes &&
                    chunk.count == first.count &&
                    chunk.index == index
            ) { "Paper custom asset payload chunks are inconsistent" }
        }
    }

    private data class Entry(
        val payloads: List<ByteArray>,
        val bytes: Int,
    )

    private companion object {
        const val DEFAULT_MAXIMUM_ENTRIES = 64
        const val DEFAULT_MAXIMUM_RETAINED_BYTES = 8 * 1_024 * 1_024
    }
}
