package me.whish.emotify.client

import com.mojang.blaze3d.platform.NativeImage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import me.whish.emotify.client.custom.CustomEmojiFile
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.client.custom.CustomEmojiFileScanner
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels

private fun NativeImage.pixelABGR(x: Int, y: Int): Int = pixelsABGR[y * width + x]

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
                decoded.frames.single().pixelABGR(0, 0) shouldBe colors[0]
                decoded.frames.single().pixelABGR(1, 0) shouldBe colors[1]
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
                decoded.frames[0].pixelABGR(0, 0) ushr 24 shouldBe 0
                decoded.frames[0].pixelABGR(1, 0) shouldBe first[1]
                decoded.frames[1].pixelABGR(1, 0) shouldBe second[1]
            } finally {
                decoded.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("animated export preserves every composed pixel from an Aseprite GIF") {
        val root = Files.createTempDirectory("emotify-aseprite-gif")
        val sourceBytes = Base64.getDecoder().decode(ASEPRITE_GIF)
        val source = CustomEmojiImageDecoder.decode(
            CustomEmojiFile(Path.of("test.gif"), "test", 16, CustomEmojiFileFormat.GIF),
            sourceBytes,
        )
        try {
            val sourceFrames = source.frames.mapIndexed { index, image ->
                CustomEmojiFrame(
                    CustomEmojiPixels.of(IntArray(image.width * image.height) { pixel ->
                        image.pixelABGR(pixel % image.width, pixel / image.width)
                    }),
                    source.durationMillisAt(index),
                )
            }
            val asset = CustomEmojiAsset.create(sourceFrames)
            val descriptor = CustomEmojiDescriptor.create("test", asset.id)
            val saved = CustomEmojiAssetExporter.export(root, asset, descriptor)
                .shouldBeInstanceOf<CustomEmojiExportResult.Saved>()
            val exportedBytes = Files.readAllBytes(saved.path)
            val exported = CustomEmojiImageDecoder.decode(
                CustomEmojiFile(saved.path, "test", 16, CustomEmojiFileFormat.GIF),
                exportedBytes,
            )
            try {
                exported.frameCount shouldBe source.frameCount
                repeat(source.frameCount) { frameIndex ->
                    exported.durationMillisAt(frameIndex) shouldBe source.durationMillisAt(frameIndex)
                    repeat(16 * 16) { pixelIndex ->
                        exported.frames[frameIndex].pixelABGR(pixelIndex % 16, pixelIndex / 16) shouldBe
                            source.frames[frameIndex].pixelABGR(pixelIndex % 16, pixelIndex / 16)
                    }
                }
            } finally {
                exported.close()
            }
        } finally {
            source.close()
            root.toFile().deleteRecursively()
        }
    }

    test("animated export clears an opaque frame before a transparent frame") {
        val root = Files.createTempDirectory("emotify-gif-disposal")
        try {
            val opaque = IntArray(64) { abgr(0xFF, 0x00, 0x00, 0xFF) }
            val transparent = IntArray(64) { abgr(0x00, 0x00, 0x00, 0x00) }.also { colors ->
                colors[0] = abgr(0x00, 0xFF, 0x00, 0xFF)
            }
            val asset = CustomEmojiAsset.create(
                listOf(
                    CustomEmojiFrame(CustomEmojiPixels.of(opaque), 100),
                    CustomEmojiFrame(CustomEmojiPixels.of(transparent), 100),
                ),
            )
            val descriptor = CustomEmojiDescriptor.create("transition", asset.id)
            val saved = CustomEmojiAssetExporter.export(root, asset, descriptor)
                .shouldBeInstanceOf<CustomEmojiExportResult.Saved>()
            val exportedBytes = Files.readAllBytes(saved.path)
            val exported = CustomEmojiImageDecoder.decode(
                CustomEmojiFile(saved.path, "transition", 8, CustomEmojiFileFormat.GIF),
                exportedBytes,
            )
            try {
                exported.frameCount shouldBe 2
                exported.frames[1].pixelABGR(0, 0) shouldBe transparent[0]
                exported.frames[1].pixelABGR(1, 0) shouldBe transparent[1]
            } finally {
                exported.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun abgr(red: Int, green: Int, blue: Int, alpha: Int): Int =
            alpha shl 24 or (blue shl 16) or (green shl 8) or red

        private const val ASEPRITE_GIF =
            "R0lGODdhEAAQAHcAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJMgAAACwAAAAAEAAQAIAAAAAAAAACIIwDcMuWDZGK7NFlXdp46u1d4kiWEth16JGJ7fVScdQWACH5BAkyAAAALAAAAAAQABAAgAAAAAAAAAImhINpy6kXmosQrUnxxXzz6X1PB4hgZVJlaqGthoYtJKuMa9/wUgAAIfkECTIAAAAsAAAAABAAEACAAAAAAAAAAiCMA3DLlg2RiuzRZV3aeOrtXeJIlhLYdeiRie31UnHUFgAh+QQJMgAAACwAAAAAEAAQAIAAAAAAAAACJoSDacupF5qLEK1J8cV88+l9TweIYGVSZWqhrYaGLSSrjGvf8FIAACH5BAkyAAAALAAAAAAQABAAgAAAAAAAAAIgjANwy5YNkYrs0WVd2njq7V3iSJYS2HXokYnt9VJx1BYAIfkEBTIAAAAsAAAAABAAEACAAAAAAAAAAiaEg2nLqReaixCtSfHFfPPpfU8HiGBlUmVqoa2Ghi0kq4xr3/BSAAA7"
    }
}
