package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import me.whish.emotify.client.custom.CustomEmojiFile
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.client.custom.GifTimelineNormalizer

@Suppress("unused", "DEPRECATION")
class CustomEmojiImageDecoderTest : FunSpec({
    test("PNG decoding preserves the final row of a sixteen pixel image") {
        val bytes = encodeImage("png", BufferedImage.TYPE_INT_ARGB) { image ->
            repeat(image.width) { x ->
                repeat(image.height) { y ->
                    image.setRGB(x, y, if (y == image.height - 1) 0xFFCC6633.toInt() else 0xFF102030.toInt())
                }
            }
        }
        val decoded = CustomEmojiImageDecoder.decode(customFile("test.png", CustomEmojiFileFormat.PNG), bytes)

        try {
            val image = decoded.frames.single()
            image.width shouldBe 16
            image.height shouldBe 16
            image.getPixelRGBA(0, 14) shouldBe 0xFF302010.toInt()
            image.getPixelRGBA(0, 15) shouldBe 0xFF3366CC.toInt()
        } finally {
            decoded.close()
        }
    }

    test("JPEG decoding creates an opaque native image with the expected channel order") {
        val bytes = encodeImage("jpg", BufferedImage.TYPE_INT_RGB) { image ->
            repeat(image.width) { x ->
                repeat(image.height) { y -> image.setRGB(x, y, 0xFFCC6633.toInt()) }
            }
        }
        val decoded = CustomEmojiImageDecoder.decode(customFile("test.jpeg", CustomEmojiFileFormat.JPEG), bytes)

        try {
            val image = decoded.frames.single()
            val color = image.getPixelRGBA(8, 8)
            image.width shouldBe 16
            image.height shouldBe 16
            (color and 0xFF).shouldBeInRange(196..212)
            (color ushr 8 and 0xFF).shouldBeInRange(94..110)
            (color ushr 16 and 0xFF).shouldBeInRange(43..59)
            (color ushr 24 and 0xFF) shouldBe 255
        } finally {
            decoded.close()
        }
    }

    test("GIF decoding preserves composed frames and bounds their effective frame rate") {
        val bytes = encodeGif(intArrayOf(2, 10))
        val decoded = CustomEmojiImageDecoder.decode(customFile("animated.gif", CustomEmojiFileFormat.GIF, 8), bytes)

        try {
            decoded.frameCount shouldBe 2
            decoded.durationMillisAt(0) shouldBe 67
            decoded.durationMillisAt(1) shouldBe 67
            decoded.frames[0].getPixelRGBA(0, 0) shouldBe 0xFF0000FF.toInt()
            decoded.frames[1].getPixelRGBA(0, 0) shouldBe 0xFF00FF00.toInt()
        } finally {
            decoded.close()
        }
    }

    test("GIF over the network frame budget is normalized without losing its tail") {
        val bytes = encodeGif(IntArray(34) { 7 })
        val decoded = CustomEmojiImageDecoder.decode(customFile("normalized.gif", CustomEmojiFileFormat.GIF, 8), bytes)

        try {
            decoded.frameCount shouldBe 30
            (0 until decoded.frameCount).sumOf(decoded::durationMillisAt) shouldBe 2_380
            decoded.frames.first().getPixelRGBA(0, 0) shouldBe 0xFF0000FF.toInt()
            decoded.frames.last().getPixelRGBA(0, 0) shouldBe 0xFF00FF00.toInt()
        } finally {
            decoded.close()
        }
    }

    test("GIF structure is rejected before native decode when it exceeds the source safety budget") {
        val bytes = encodeGif(IntArray(GifTimelineNormalizer.MAXIMUM_SOURCE_FRAME_COUNT + 1) { 7 })

        shouldThrow<IllegalArgumentException> {
            CustomEmojiImageDecoder.decode(customFile("oversized.gif", CustomEmojiFileFormat.GIF, 8), bytes)
        }
    }

    test("long GIF timing is clipped at the emotion lifecycle without global acceleration") {
        val bytes = encodeGif(intArrayOf(200, 200))
        val decoded = CustomEmojiImageDecoder.decode(customFile("long.gif", CustomEmojiFileFormat.GIF, 8), bytes)

        try {
            decoded.durationMillisAt(0) shouldBe 2_000
            decoded.durationMillisAt(1) shouldBe 1_000
        } finally {
            decoded.close()
        }
    }
}) {
    companion object {
        private fun customFile(name: String, format: CustomEmojiFileFormat, size: Int = 16): CustomEmojiFile = CustomEmojiFile(
            Path.of(name),
            name.substringBeforeLast('.'),
            size,
            format,
        )

        private fun encodeImage(
            format: String,
            type: Int,
            draw: (BufferedImage) -> Unit,
        ): ByteArray {
            val image = BufferedImage(16, 16, type)
            draw(image)
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, format, output))
                output.toByteArray()
            }
        }

        private fun encodeGif(delaysHundredths: IntArray): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            return ByteArrayOutputStream().use { bytes ->
                ImageIO.createImageOutputStream(bytes).use { output ->
                    writer.output = output
                    writer.prepareWriteSequence(null)
                    try {
                        delaysHundredths.forEachIndexed { index, delay ->
                            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
                            val graphics = image.createGraphics()
                            try {
                                graphics.color = if (index % 2 == 0) Color.RED else Color.GREEN
                                graphics.fillRect(0, 0, image.width, image.height)
                            } finally {
                                graphics.dispose()
                            }
                            val parameters = writer.defaultWriteParam
                            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), parameters)
                            val format = metadata.nativeMetadataFormatName
                            val root = metadata.getAsTree(format) as IIOMetadataNode
                            val control = root.node("GraphicControlExtension")
                            control.setAttribute("disposalMethod", "none")
                            control.setAttribute("userInputFlag", "FALSE")
                            control.setAttribute("transparentColorFlag", "FALSE")
                            control.setAttribute("delayTime", delay.toString())
                            control.setAttribute("transparentColorIndex", "0")
                            metadata.setFromTree(format, root)
                            writer.writeToSequence(IIOImage(image, null, metadata), parameters)
                        }
                    } finally {
                        writer.endWriteSequence()
                        writer.dispose()
                    }
                }
                bytes.toByteArray()
            }
        }

        private fun IIOMetadataNode.node(name: String): IIOMetadataNode {
            var child = firstChild
            while (child != null) {
                if (child.nodeName == name) {
                    return child as IIOMetadataNode
                }
                child = child.nextSibling
            }
            return IIOMetadataNode(name).also(::appendChild)
        }
    }
}
