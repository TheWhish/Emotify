package me.whish.emotify.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.protocol.CustomEmojiAssetChunk

@Suppress("unused")
class PaperCustomAssetChunkPayloadCacheTest : FunSpec({
    fun chunk(value: Long): CustomEmojiAssetChunk = CustomEmojiAssetChunk(
        CustomEmojiId(value, value + 1, value + 2),
        1,
        0,
        1,
        byteArrayOf(value.toByte()),
    )

    test("framed custom asset payloads are encoded once per retained content id") {
        var encodes = 0
        val cache = PaperCustomAssetChunkPayloadCache(2, 128) {
            encodes += 1
            ByteArray(8)
        }
        val assetChunk = chunk(1)

        val first = cache.payloads(assetChunk.customEmojiId, listOf(assetChunk))
        val second = cache.payloads(assetChunk.customEmojiId, listOf(assetChunk))

        (first === second) shouldBe true
        encodes shouldBe 1
        cache.clear()
        cache.payloads(assetChunk.customEmojiId, listOf(assetChunk))
        encodes shouldBe 2
    }

    test("framed custom asset payload cache evicts the least recently used entry") {
        val encodes = HashMap<CustomEmojiId, Int>()
        val cache = PaperCustomAssetChunkPayloadCache(2, 128) { assetChunk ->
            encodes[assetChunk.customEmojiId] = encodes.getOrDefault(assetChunk.customEmojiId, 0) + 1
            ByteArray(8)
        }
        val first = chunk(1)
        val second = chunk(4)
        val third = chunk(7)

        cache.payloads(first.customEmojiId, listOf(first))
        cache.payloads(second.customEmojiId, listOf(second))
        cache.payloads(first.customEmojiId, listOf(first))
        cache.payloads(third.customEmojiId, listOf(third))
        cache.payloads(first.customEmojiId, listOf(first))
        cache.payloads(second.customEmojiId, listOf(second))

        encodes[first.customEmojiId] shouldBe 1
        encodes[second.customEmojiId] shouldBe 2
        encodes[third.customEmojiId] shouldBe 1
    }

    test("framed custom asset payload cache enforces its retained byte budget") {
        val encodes = HashMap<CustomEmojiId, Int>()
        val cache = PaperCustomAssetChunkPayloadCache(10, 10) { assetChunk ->
            encodes[assetChunk.customEmojiId] = encodes.getOrDefault(assetChunk.customEmojiId, 0) + 1
            ByteArray(if (assetChunk.customEmojiId.mostSignificantBits == 7L) 11 else 6)
        }
        val first = chunk(1)
        val second = chunk(4)
        val oversized = chunk(7)

        cache.payloads(first.customEmojiId, listOf(first))
        cache.payloads(second.customEmojiId, listOf(second))
        cache.payloads(first.customEmojiId, listOf(first))
        cache.payloads(oversized.customEmojiId, listOf(oversized))
        cache.payloads(oversized.customEmojiId, listOf(oversized))

        encodes[first.customEmojiId] shouldBe 2
        encodes[second.customEmojiId] shouldBe 1
        encodes[oversized.customEmojiId] shouldBe 2
    }

    test("framed custom asset payload cache rejects incomplete chunk sets") {
        val cache = PaperCustomAssetChunkPayloadCache()
        val id = CustomEmojiId(1, 2, 3)
        val incomplete = CustomEmojiAssetChunk(id, 2, 0, 2, byteArrayOf(1))

        shouldThrow<IllegalArgumentException> {
            cache.payloads(id, emptyList())
        }
        shouldThrow<IllegalArgumentException> {
            cache.payloads(id, listOf(incomplete))
        }
    }
})
