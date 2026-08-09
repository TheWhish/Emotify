package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import me.whish.emotify.client.custom.CustomEmojiFileScanner
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels

@Suppress("unused", "DEPRECATION")
class CustomEmojiAssetExporterTest : FunSpec({
    test("static export preserves pixels name and origin in PNG metadata") {
        val root = Files.createTempDirectory("emotify-shared-png")
        try {
            val colors = IntArray(64) { index ->
                if (index == 0) abgr(0x12, 0x34, 0x56, 0x78) else abgr(0xCC, 0x66, 0x33, 0xFF)
            }
            val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(colors))
            val descriptor = CustomEmojiDescriptor.create("Happy", asset.id)

            val saved = CustomEmojiAssetExporter.export(root, asset, descriptor).shouldBeInstanceOf<CustomEmojiExportResult.Saved>()
            saved.path.fileName.toString() shouldBe "Happy.png"
            val scan = CustomEmojiFileScanner.scan(root)
            scan.accepted shouldHaveSize 1
            val bytes = Files.readAllBytes(saved.path)
            CustomEmojiEmbeddedDescriptor.read(scan.accepted.single().format, bytes) shouldBe descriptor
            val decoded = CustomEmojiImageDecoder.decode(scan.accepted.single(), bytes)
            try {
                decoded.frames.single().getPixelRGBA(0, 0) shouldBe colors[0]
                decoded.frames.single().getPixelRGBA(1, 0) shouldBe colors[1]
            } finally {
                decoded.close()
            }

            val second = CustomEmojiAssetExporter.export(root, asset, descriptor).shouldBeInstanceOf<CustomEmojiExportResult.Saved>()
            second.path.fileName.toString() shouldBe "Happy (2).png"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("animated export preserves frame order transparency and cycle timing") {
        val root = Files.createTempDirectory("emotify-shared-gif")
        try {
            val first = IntArray(64) { abgr(0xFF, 0x00, 0x00, 0xFF) }.also { colors ->
                colors[0] = abgr(0x00, 0x00, 0x00, 0x00)
            }
            val second = IntArray(64) { abgr(0x00, 0xFF, 0x00, 0xFF) }
            val asset = CustomEmojiAsset.create(
                listOf(
                    CustomEmojiFrame(CustomEmojiPixels.of(first), 100),
                    CustomEmojiFrame(CustomEmojiPixels.of(second), 200),
                ),
            )
            val descriptor = CustomEmojiDescriptor.create("Танец", asset.id)

            val saved = CustomEmojiAssetExporter.export(root, asset, descriptor).shouldBeInstanceOf<CustomEmojiExportResult.Saved>()
            saved.path.fileName.toString() shouldBe "Танец.gif"
            val scan = CustomEmojiFileScanner.scan(root)
            scan.accepted shouldHaveSize 1
            val bytes = Files.readAllBytes(saved.path)
            CustomEmojiEmbeddedDescriptor.read(scan.accepted.single().format, bytes) shouldBe descriptor
            val decoded = CustomEmojiImageDecoder.decode(scan.accepted.single(), bytes)
            try {
                decoded.frameCount shouldBe 2
                decoded.durationMillisAt(0) shouldBe 100
                decoded.durationMillisAt(1) shouldBe 200
                decoded.frames[0].getPixelRGBA(0, 0) ushr 24 shouldBe 0
                decoded.frames[0].getPixelRGBA(1, 0) shouldBe first[1]
                decoded.frames[1].getPixelRGBA(1, 0) shouldBe second[1]
            } finally {
                decoded.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun abgr(red: Int, green: Int, blue: Int, alpha: Int): Int =
            alpha shl 24 or (blue shl 16) or (green shl 8) or red
    }
}
