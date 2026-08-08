package me.whish.emotify.wire.v1

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.SplittableRandom
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.protocol.CustomEmojiAssetChunk

@Suppress("unused")
class CustomEmojiLosslessTransferTest : FunSpec({
    test("one hundred twenty eight pixel static asset round trips without changing a pixel") {
        val pixels = CustomEmojiPixels.of(128, IntArray(128 * 128) { index ->
            val x = index % 128
            val y = index / 128
            0xFF000000.toInt() or (x shl 16) or (y shl 8) or (x xor y)
        })
        val asset = CustomEmojiAsset.create(pixels)

        val encoded = CustomEmojiLosslessCodec.encode(asset)
        val decoded = CustomEmojiLosslessCodec.decode(asset.id, encoded)

        decoded shouldBe asset
        decoded.pixels.copyColors().contentEquals(pixels.copyColors()) shouldBe true
        encoded.size shouldBe CustomEmojiLosslessCodec.encodedSize(asset)
    }

    test("sixty four pixel animation round trips every frame and duration") {
        val frames = List(30) { frameIndex ->
            CustomEmojiFrame(
                CustomEmojiPixels.of(64, IntArray(64 * 64) { pixelIndex ->
                    val x = pixelIndex % 64
                    val y = pixelIndex / 64
                    if (x in frameIndex until frameIndex + 8 && y in 24..39) {
                        0xFFFFCC00.toInt()
                    } else {
                        0
                    }
                }),
                if (frameIndex < 2) 95 else 67,
            )
        }
        val asset = CustomEmojiAsset.create(frames)

        val encoded = CustomEmojiLosslessCodec.encode(asset)
        val decoded = CustomEmojiLosslessCodec.decode(asset.id, encoded)

        decoded shouldBe asset
        decoded.frames.map(CustomEmojiFrame::durationMillis) shouldBe frames.map(CustomEmojiFrame::durationMillis)
        encoded.size shouldBeLessThan asset.rawByteLength
    }

    test("incompressible pixels remain bounded and lossless") {
        var state = 0x13579BDF
        val pixels = CustomEmojiPixels.of(128, IntArray(128 * 128) {
            state = state * 1_103_515_245 + 12_345
            state
        })
        val asset = CustomEmojiAsset.create(pixels)

        val encoded = CustomEmojiLosslessCodec.encode(asset)

        encoded.size shouldBeLessThanOrEqual CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES
        CustomEmojiLosslessCodec.decode(asset.id, encoded) shouldBe asset
    }

    test("lossless transfer chunks remain portable and reassemble in order") {
        val pixels = CustomEmojiPixels.of(128, IntArray(128 * 128) { it * 0x01010101 })
        val asset = CustomEmojiAsset.create(pixels)
        val chunks = CustomEmojiAssetChunker.split(asset)
        val assembler = CustomEmojiAssetAssembler()

        chunks.all { chunk ->
            ProtocolV1Codecs.customAssetChunk.encodedSize(chunk) <= ProtocolV1Limits.CUSTOM_ASSET_CHUNK_BODY_BYTES
        } shouldBe true
        (chunks.size <= CustomEmojiAssetChunker.MAXIMUM_CHUNK_COUNT) shouldBe true
        chunks.all { chunk ->
            ProtocolV1Codecs.customAssetChunk.encodedSize(chunk) - chunk.dataLength <=
                CustomEmojiAssetChunker.MAXIMUM_CHUNK_HEADER_BYTES
        } shouldBe true
        (chunks.sumOf(ProtocolV1Codecs.customAssetChunk::encodedSize) <=
            CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES) shouldBe true
        chunks.forEachIndexed { index, chunk ->
            assembler.accept(chunk, index * 100L) shouldBe if (index == chunks.lastIndex) asset else null
        }
    }

    test("assembler rejects out of order conflicting and expired chunks") {
        var state = 0x2468ACE
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) {
            state = state * 1_103_515_245 + 12_345
            state
        }))
        val chunks = CustomEmojiAssetChunker.split(asset)
        val assembler = CustomEmojiAssetAssembler(timeoutMillis = 1_000)

        (chunks.size > 2) shouldBe true
        assembler.accept(chunks.first(), 0L)
        shouldThrow<WireDecodeException> {
            assembler.accept(chunks[2], 100L)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI

        assembler.accept(chunks.first(), 200L)
        shouldThrow<WireDecodeException> {
            assembler.accept(chunks[1], 1_201L)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI
    }

    test("safe assembly rejects malformed state and recovers for the next transfer") {
        val random = SplittableRandom(0x13579BDF)
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { random.nextInt() }))
        val chunks = CustomEmojiAssetChunker.split(asset)
        val assembler = CustomEmojiAssetAssembler()

        assembler.tryAcceptAssembly(chunks[1], 0L) shouldBe
            CustomEmojiAssetAssemblyResult.Rejected(WireDecodeViolation.INVALID_CUSTOM_EMOJI)
        chunks.forEachIndexed { index, chunk ->
            val result = assembler.tryAcceptAssembly(chunk, index.toLong())
            if (index == chunks.lastIndex) {
                result.shouldBeInstanceOf<CustomEmojiAssetAssemblyResult.Completed>().assembly.asset shouldBe asset
            } else {
                result shouldBe CustomEmojiAssetAssemblyResult.Pending
            }
        }
    }

    test("assembler validates direct chunks before allocating declared capacity") {
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it * 31 }))
        val malformed = CustomEmojiAssetChunk(
            asset.id,
            totalBytes = 1,
            index = 0,
            count = Int.MAX_VALUE,
            data = byteArrayOf(1),
        )
        val assembler = CustomEmojiAssetAssembler()

        shouldThrow<WireEncodeException> {
            ProtocolV1Codecs.customAssetChunk.encodedSize(malformed)
        }.violation shouldBe WireEncodeViolation.UNENCODABLE_VALUE
        assembler.tryAcceptAssembly(malformed, 0L) shouldBe
            CustomEmojiAssetAssemblyResult.Rejected(WireDecodeViolation.INVALID_CUSTOM_EMOJI)
        val valid = CustomEmojiAssetChunker.split(asset)
        valid.forEachIndexed { index, chunk ->
            val result = assembler.tryAcceptAssembly(chunk, index.toLong())
            if (index == valid.lastIndex) {
                result.shouldBeInstanceOf<CustomEmojiAssetAssemblyResult.Completed>().assembly.asset shouldBe asset
            } else {
                result shouldBe CustomEmojiAssetAssemblyResult.Pending
            }
        }
    }

    test("tampered compressed content fails its content identity") {
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))
        val encoded = CustomEmojiLosslessCodec.encode(asset)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        shouldThrow<WireDecodeException> {
            CustomEmojiLosslessCodec.decode(asset.id, encoded)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI
    }

    test("bounded transfer cache reuses an encoded asset and clears deterministically") {
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))
        val cache = CustomEmojiAssetChunkCache(maximumEntries = 1)

        val first = cache.chunks(asset)
        (cache.chunks(asset) === first) shouldBe true

        cache.clear()
        (cache.chunks(asset) === first) shouldBe false
    }
})
