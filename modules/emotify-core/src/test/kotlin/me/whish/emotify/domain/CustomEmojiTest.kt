package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

@Suppress("unused")
class CustomEmojiTest : FunSpec({
    test("content ID is deterministic and independent from mutable input") {
        val source = IntArray(64) { index -> index * 0x01010101 }
        val pixels = CustomEmojiPixels.of(source)
        val id = CustomEmojiId.fromPixels(pixels)
        source.fill(0)

        CustomEmojiId.fromPixels(pixels) shouldBe id
        id.hexValue().length shouldBe CustomEmojiId.HEX_LENGTH
        CustomEmojiId.parse(id.emotionId) shouldBe id
    }

    test("different canonical pixels produce different IDs") {
        val first = CustomEmojiPixels.of(IntArray(64))
        val second = CustomEmojiPixels.of(IntArray(64).also { it[63] = 1 })

        CustomEmojiId.fromPixels(first) shouldNotBe CustomEmojiId.fromPixels(second)
    }

    test("asset verification rejects a mismatched content ID") {
        val first = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64)))
        val secondPixels = CustomEmojiPixels.of(IntArray(64) { 1 })

        CustomEmojiAsset.verify(first.id, secondPixels) shouldBe null
    }

    test("asset verification does not swallow fatal errors") {
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64)))
        val failingFrames = object : AbstractList<CustomEmojiFrame>() {
            override val size: Int = 1

            override fun get(index: Int): CustomEmojiFrame = throw AssertionError("fatal decoder failure")
        }

        shouldThrow<AssertionError> { CustomEmojiAsset.verify(asset.id, failingFrames) }
    }

    test("pixel value rejects invalid dimensions and protects its storage") {
        shouldThrow<IllegalArgumentException> { CustomEmojiPixels.of(IntArray(63)) }
        shouldThrow<IllegalArgumentException> { CustomEmojiPixels.of(IntArray(65)) }
        val pixels = CustomEmojiPixels.of(IntArray(64) { it })
        val copy = pixels.copyColors()
        copy[0] = -1

        pixels.colorAt(0) shouldBe 0
        shouldThrow<IllegalArgumentException> { pixels.colorAt(64) }
    }

    test("supported static dimensions preserve their exact content") {
        val supported = listOf(8, 16, 32, 64, 128).map { size ->
            CustomEmojiPixels.of(size, IntArray(size * size) { it })
        }

        supported.map(CustomEmojiPixels::size) shouldBe listOf(8, 16, 32, 64, 128)
        supported.forEach { pixels ->
            pixels.pixelCount shouldBe pixels.size * pixels.size
            pixels.colorAt(pixels.pixelCount - 1) shouldBe pixels.pixelCount - 1
        }
        supported.map(CustomEmojiId::fromPixels).toSet().size shouldBe supported.size
        shouldThrow<IllegalArgumentException> { CustomEmojiPixels.of(24, IntArray(24 * 24)) }
    }

    test("animated asset identity includes every frame and duration") {
        val first = CustomEmojiPixels.of(IntArray(64) { 0xFF000000.toInt() or it })
        val second = CustomEmojiPixels.of(IntArray(64) { 0xFFFFFFFF.toInt() - it })
        val asset = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(first, 67),
                CustomEmojiFrame(second, 133),
            ),
        )
        val changedTiming = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(first, 68),
                CustomEmojiFrame(second, 132),
            ),
        )

        asset.isAnimated shouldBe true
        asset.cycleDurationMillis shouldBe 200
        asset.rawByteLength shouldBe 512
        CustomEmojiAsset.verify(asset.id, asset.frames) shouldBe asset
        changedTiming.id shouldNotBe asset.id
    }

    test("animated asset rejects unsafe frame counts durations dimensions and cycles") {
        val small = CustomEmojiPixels.of(IntArray(64))
        val large = CustomEmojiPixels.of(IntArray(256))

        shouldThrow<IllegalArgumentException> { CustomEmojiFrame(small, 66) }
        shouldThrow<IllegalArgumentException> {
            CustomEmojiAsset.create(listOf(CustomEmojiFrame(small, 67), CustomEmojiFrame(large, 67)))
        }
        shouldThrow<IllegalArgumentException> {
            CustomEmojiAsset.create(List(6) { CustomEmojiFrame(small, 2_000) })
        }
        shouldThrow<IllegalArgumentException> {
            CustomEmojiAsset.create(List(31) { CustomEmojiFrame(small, 67) })
        }
        val oversized = CustomEmojiPixels.of(128, IntArray(128 * 128))
        shouldThrow<IllegalArgumentException> {
            CustomEmojiAsset.create(
                listOf(
                    CustomEmojiFrame(oversized, 67),
                    CustomEmojiFrame(oversized, 67),
                ),
            )
        }
    }

    test("animated asset accepts the complete three second lifecycle and rejects longer cycles") {
        val first = CustomEmojiPixels.of(IntArray(64))
        val second = CustomEmojiPixels.of(IntArray(64) { 1 })

        val complete = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(first, 1_500),
                CustomEmojiFrame(second, 1_500),
            ),
        )

        complete.cycleDurationMillis shouldBe 3_000
        shouldThrow<IllegalArgumentException> {
            CustomEmojiAsset.create(
                listOf(
                    CustomEmojiFrame(first, 1_500),
                    CustomEmojiFrame(second, 1_501),
                ),
            )
        }
    }

    test("sixty four pixel animation preserves thirty exact frames") {
        val frames = List(CustomEmojiAsset.MAXIMUM_FRAME_COUNT) { frameIndex ->
            CustomEmojiFrame(
                CustomEmojiPixels.of(64, IntArray(64 * 64) { pixelIndex -> frameIndex shl 24 or pixelIndex }),
                if (frameIndex < 2) 95 else 67,
            )
        }

        val asset = CustomEmojiAsset.create(frames)

        asset.pixels.size shouldBe 64
        asset.frames.size shouldBe 30
        asset.cycleDurationMillis shouldBe 2_066
        asset.rawByteLength shouldBe 64 * 64 * 4 * 30
    }
})
