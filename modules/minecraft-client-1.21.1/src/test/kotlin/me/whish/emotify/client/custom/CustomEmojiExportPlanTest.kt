package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels

@Suppress("unused")
class CustomEmojiExportPlanTest : FunSpec({
    fun pixels(seed: Int): CustomEmojiPixels = CustomEmojiPixels.of(IntArray(64) { seed + it })

    test("static assets receive deterministic lossless PNG names") {
        val asset = CustomEmojiAsset.create(pixels(1))

        CustomEmojiExportPlan.forAsset(asset, CustomEmojiDescriptor.create("Happy", asset.id)) shouldBe CustomEmojiExportPlan(
            fileName = "Happy.png",
            format = CustomEmojiExportFormat.PNG,
        )
    }

    test("animated assets receive deterministic GIF names") {
        val asset = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(pixels(1), 100),
                CustomEmojiFrame(pixels(2), 200),
            ),
        )

        CustomEmojiExportPlan.forAsset(asset, CustomEmojiDescriptor.create("Весёлый танец", asset.id)) shouldBe CustomEmojiExportPlan(
            fileName = "Весёлый танец.gif",
            format = CustomEmojiExportFormat.GIF,
        )
    }

    test("filesystem-hostile names become safe readable filenames") {
        val asset = CustomEmojiAsset.create(pixels(1))

        CustomEmojiExportPlan.forAsset(
            asset,
            CustomEmojiDescriptor.create("../CON:<dance>?*", asset.id),
        ).fileName shouldBe "_CON__dance___.png"
    }

    test("Windows device names remain safe when the display name contains an extension") {
        val asset = CustomEmojiAsset.create(pixels(1))

        CustomEmojiExportPlan.forAsset(
            asset,
            CustomEmojiDescriptor.create("CON.txt", asset.id),
        ).fileName shouldBe "_CON.txt.png"
        CustomEmojiExportPlan.forAsset(
            asset,
            CustomEmojiDescriptor.create("com1.backup", asset.id),
        ).fileName shouldBe "_com1.backup.png"
    }

    test("GIF timing keeps the rounded cycle and legal frame delays") {
        val delays = GifFrameTiming.quantizeToCentiseconds(listOf(67, 67, 67, 799))

        delays.sum() shouldBe 100
        delays.forEach { delay -> delay shouldBeGreaterThanOrEqual 7 }
        delays.toList() shouldBe listOf(7, 7, 7, 79)
    }

    test("GIF timing preserves exact already-quantized durations") {
        GifFrameTiming.quantizeToCentiseconds(listOf(100, 200, 700)).toList() shouldBe
            listOf(10, 20, 70)
    }

    test("embedded descriptor codec preserves Unicode name and origin") {
        val asset = CustomEmojiAsset.create(pixels(1))
        val descriptor = CustomEmojiDescriptor.create("Танец 🐸", asset.id)

        CustomEmojiEmbeddedDescriptorCodec.decode(
            CustomEmojiEmbeddedDescriptorCodec.encode(descriptor),
        ) shouldBe descriptor
        CustomEmojiEmbeddedDescriptorCodec.decode("invalid") shouldBe null
    }
})
