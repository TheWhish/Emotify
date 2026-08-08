package me.whish.emotify.wire.v1

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId

@Suppress("unused")
class ProtocolV1CustomEmojiTest : FunSpec({
    val pixels = CustomEmojiPixels.of(IntArray(64) { index ->
        if (index % 3 == 0) 0 else 0xFF00FFFF.toInt()
    })
    val asset = CustomEmojiAsset.create(pixels)
    val largePixels = CustomEmojiPixels.of(16, IntArray(256) { index ->
        when {
            index >= 240 -> 0xFFFF0000.toInt()
            index % 2 == 0 -> 0xFFFFFFFF.toInt()
            else -> 0xFF0000FF.toInt()
        }
    })
    val largeAsset = CustomEmojiAsset.create(largePixels)

    test("custom selection round trips inline and by reference") {
        val inline = CustomEmotionSelection(asset.id, asset)
        val reference = CustomEmotionSelection(asset.id, null)

        ProtocolV1Codecs.customSelection.decode(
            ProtocolV1Codecs.customSelection.encodeToByteArray(inline),
        ).let { decoded ->
            decoded.customEmojiId shouldBe asset.id
            decoded.asset?.pixels shouldBe pixels
        }
        ProtocolV1Codecs.customSelection.decode(
            ProtocolV1Codecs.customSelection.encodeToByteArray(reference),
        ) shouldBe reference
    }

    test("palette transfer is lossless and smaller than raw pixels") {
        val encoded = ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(asset))
        val decoded = ProtocolV1Codecs.customAsset.decode(encoded)

        decoded.asset.id shouldBe asset.id
        decoded.asset.pixels shouldBe pixels
        encoded.size shouldBe 44
    }

    test("palette colors preserve deterministic first-seen order") {
        val first = 0x99AABBCC.toInt()
        val second = 0x11223344
        val third = 0x55667788
        val orderedPixels = CustomEmojiPixels.of(IntArray(64) { index ->
            when (index % 4) {
                0, 2 -> first
                1 -> second
                else -> third
            }
        })
        val encoded = ProtocolV1Codecs.customAsset.encodeToByteArray(
            CustomEmojiTransfer(CustomEmojiAsset.create(orderedPixels)),
        )

        encoded.copyOfRange(CustomEmojiId.BYTE_LENGTH, CustomEmojiId.BYTE_LENGTH + 16).toList() shouldBe
            hex("08 01 03 99 AA BB CC 11 22 33 44 55 66 77 88 02").toList()
        ProtocolV1Codecs.customAsset.decode(encoded).asset.pixels shouldBe orderedPixels
    }

    test("sixteen pixel transfer preserves every row without resampling") {
        val encoded = ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(largeAsset))
        val decoded = ProtocolV1Codecs.customAsset.decode(encoded)

        decoded.asset.pixels shouldBe largePixels
        decoded.asset.pixels.size shouldBe 16
        decoded.asset.pixels.colorAt(255) shouldBe 0xFFFF0000.toInt()
        (encoded.size < largePixels.rawByteLength) shouldBe true
    }

    test("custom play has a fixed content ID and round trips") {
        val play = CustomEmotionPlay(
            RuntimeEntityId.of(300),
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            EventSequence.of(300),
            asset.id,
        )

        ProtocolV1Codecs.customPlay.decode(ProtocolV1Codecs.customPlay.encodeToByteArray(play)) shouldBe play
        play.asEmotionPlay().emotionId shouldBe asset.id.emotionId
    }

    test("animated custom emoji round trips with exact frame timing") {
        val animated = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(pixels, 67),
                CustomEmojiFrame(CustomEmojiPixels.of(IntArray(64) { it * 17 }), 133),
                CustomEmojiFrame(CustomEmojiPixels.of(IntArray(64) { it * 31 }), 200),
            ),
        )

        val decoded = ProtocolV1Codecs.customAsset.decode(
            ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(animated)),
        ).asset

        decoded shouldBe animated
        decoded.frames.map(CustomEmojiFrame::durationMillis) shouldBe listOf(67, 133, 200)
    }

    test("maximum animated payload remains below the portable plugin message ceiling") {
        val maximum = CustomEmojiAsset.create(
            List(CustomEmojiAsset.MAXIMUM_FRAME_COUNT) { frameIndex ->
                CustomEmojiFrame(
                    CustomEmojiPixels.of(16, IntArray(256) { pixelIndex -> frameIndex shl 16 or pixelIndex }),
                    if (frameIndex < 3) 128 else 67,
                )
            },
        )

        ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(maximum)).size shouldBe 30_810
    }

    test("legacy encoders reject dimensions reserved for lossless transfer") {
        val staticAssets = listOf(32, 64, 128).map { size ->
            CustomEmojiAsset.create(CustomEmojiPixels.of(size, IntArray(size * size) { 0xFF336699.toInt() }))
        }
        val animatedAssets = listOf(32, 64).map { size ->
            CustomEmojiAsset.create(
                listOf(
                    CustomEmojiFrame(CustomEmojiPixels.of(size, IntArray(size * size) { 0xFF336699.toInt() }), 67),
                    CustomEmojiFrame(CustomEmojiPixels.of(size, IntArray(size * size) { 0xFF993366.toInt() }), 67),
                ),
            )
        }

        (staticAssets + animatedAssets).forEach { oversized ->
            shouldThrow<WireEncodeException> {
                ProtocolV1Codecs.customAsset.encodedSize(CustomEmojiTransfer(oversized))
            }.violation shouldBe WireEncodeViolation.UNENCODABLE_VALUE
        }
        shouldThrow<WireEncodeException> {
            ProtocolV1Codecs.customSelection.encodeToByteArray(
                CustomEmotionSelection(staticAssets.first().id, staticAssets.first()),
            )
        }.violation shouldBe WireEncodeViolation.UNENCODABLE_VALUE
    }

    test("animated decoder rejects excessive frame counts and sub-limit durations before allocation") {
        val animated = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(pixels, 67),
                CustomEmojiFrame(CustomEmojiPixels.of(IntArray(64) { it }), 67),
            ),
        )
        val excessiveFrames = ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(animated))
        excessiveFrames[26] = 31
        val shortDuration = ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(animated))
        shortDuration[27] = 66

        shouldThrow<WireDecodeException> {
            ProtocolV1Codecs.customAsset.decode(excessiveFrames)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI
        shouldThrow<WireDecodeException> {
            ProtocolV1Codecs.customAsset.decode(shortDuration)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI
    }

    test("tampered custom content is rejected before reaching runtime") {
        val encoded = ProtocolV1Codecs.customAsset.encodeToByteArray(CustomEmojiTransfer(asset))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        shouldThrow<WireDecodeException> {
            ProtocolV1Codecs.customAsset.decode(encoded)
        }.violation shouldBe WireDecodeViolation.INVALID_CUSTOM_EMOJI
    }

    test("custom payload limits remain compact") {
        ProtocolV1Codecs.customSelection.maxBodyBytes shouldBe 30_811
        ProtocolV1Codecs.customAsset.maxBodyBytes shouldBe 30_810
        ProtocolV1Codecs.customPlay.maxBodyBytes shouldBe 55
    }
})
