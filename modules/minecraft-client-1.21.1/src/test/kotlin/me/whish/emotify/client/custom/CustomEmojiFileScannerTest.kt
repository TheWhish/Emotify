package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

@Suppress("unused")
class CustomEmojiFileScannerTest : FunSpec({
    test("creates the emoji directory and discovers png jpg jpeg and gif files in stable order") {
        val root = Files.createTempDirectory("emotify-custom-emoji")
        val directory = root.resolve("emoji")

        try {
            Files.createDirectories(directory)
            writePng(directory.resolve("Zebra.PNG"), 16, 16)
            writePng(directory.resolve("alpha.png"), 8, 8)
            writeJpeg(directory.resolve("bravo.jpg"), 16, 16)
            writeJpeg(directory.resolve("charlie.JPEG"), 8, 8)
            writeGif(directory.resolve("delta.gif"), 16, 16)
            writePng(directory.resolve("echo.png"), 128, 128)
            writeGif(directory.resolve("foxtrot.gif"), 64, 64)
            Files.writeString(directory.resolve("ignored.txt"), "ignored")

            val scan = CustomEmojiFileScanner.scan(directory)

            scan.accepted.map(CustomEmojiFile::displayName) shouldContainExactly
                listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "Zebra")
            scan.accepted.map(CustomEmojiFile::sourceSize) shouldContainExactly listOf(8, 16, 8, 16, 128, 64, 16)
            scan.accepted.map(CustomEmojiFile::format) shouldContainExactly listOf(
                CustomEmojiFileFormat.PNG,
                CustomEmojiFileFormat.JPEG,
                CustomEmojiFileFormat.JPEG,
                CustomEmojiFileFormat.GIF,
                CustomEmojiFileFormat.PNG,
                CustomEmojiFileFormat.GIF,
                CustomEmojiFileFormat.PNG,
            )
            scan.rejected shouldBe emptyList()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("rejects malformed unsupported oversized and non-square image files") {
        val root = Files.createTempDirectory("emotify-custom-emoji-invalid")
        val directory = root.resolve("emoji")

        try {
            Files.createDirectories(directory)
            Files.write(directory.resolve("broken.png"), byteArrayOf(1, 2, 3, 4))
            Files.write(directory.resolve("broken.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            Files.writeString(directory.resolve("broken.gif"), "GIF89a")
            writePng(directory.resolve("unsupported.png"), 24, 24)
            writeGif(directory.resolve("large.gif"), 128, 128)
            writePng(directory.resolve("wide.png"), 16, 8)
            Files.write(
                directory.resolve("oversized.png"),
                ByteArray(CustomEmojiFileScanner.MAXIMUM_FILE_BYTES + 1),
            )

            val scan = CustomEmojiFileScanner.scan(directory)

            scan.accepted shouldBe emptyList()
            scan.rejected.map(CustomEmojiFileRejection::reason).toSet() shouldBe setOf(
                CustomEmojiFileRejectionReason.INVALID_IMAGE,
                CustomEmojiFileRejectionReason.UNSUPPORTED_DIMENSIONS,
                CustomEmojiFileRejectionReason.FILE_TOO_LARGE,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("bounds accepted files without traversing an unbounded directory") {
        val root = Files.createTempDirectory("emotify-custom-emoji-bounded")
        val directory = root.resolve("emoji")

        try {
            Files.createDirectories(directory)
            repeat(CustomEmojiFileScanner.MAXIMUM_FILES + 20) { index ->
                writePng(directory.resolve("emoji_${index.toString().padStart(3, '0')}.png"), 8, 8)
            }

            val scan = CustomEmojiFileScanner.scan(directory)

            scan.accepted.size shouldBe CustomEmojiFileScanner.MAXIMUM_FILES
            scan.fingerprint.directoryLimitReached shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("missing emoji directory is created as an empty library") {
        val root = Files.createTempDirectory("emotify-custom-emoji-missing")
        val directory = root.resolve("emoji")

        try {
            val scan = CustomEmojiFileScanner.scan(directory)

            Files.isDirectory(directory) shouldBe true
            scan.accepted shouldBe emptyList()
            scan.rejected shouldBe emptyList()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("loaded bytes must still match the dimensions accepted during scanning") {
        val root = Files.createTempDirectory("emotify-custom-emoji-replaced")
        val directory = root.resolve("emoji")

        try {
            Files.createDirectories(directory)
            val acceptedPath = directory.resolve("accepted.png")
            val replacementPath = directory.resolve("replacement.png")
            writePng(acceptedPath, 16, 16)
            writePng(replacementPath, 128, 128)
            val accepted = CustomEmojiFileScanner.scan(directory).accepted.single { file ->
                file.path == acceptedPath
            }

            CustomEmojiFileScanner.matchesExpectedImage(accepted, Files.readAllBytes(acceptedPath)) shouldBe true
            CustomEmojiFileScanner.matchesExpectedImage(accepted, Files.readAllBytes(replacementPath)) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("directory fingerprint changes after supported image creation modification and deletion") {
        val root = Files.createTempDirectory("emotify-custom-emoji-fingerprint")
        val directory = root.resolve("emoji")

        try {
            val empty = CustomEmojiFileScanner.fingerprint(directory)
            val path = directory.resolve("dynamic.png")
            writePng(path, 8, 8)
            val created = CustomEmojiFileScanner.fingerprint(directory)
            Files.write(path, Files.readAllBytes(path) + byteArrayOf(0))
            val modified = CustomEmojiFileScanner.fingerprint(directory)
            Files.delete(path)
            val deleted = CustomEmojiFileScanner.fingerprint(directory)

            (empty != created) shouldBe true
            (created != modified) shouldBe true
            deleted shouldBe empty
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun writePng(path: java.nio.file.Path, width: Int, height: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            check(ImageIO.write(image, "png", path.toFile()))
        }

        private fun writeJpeg(path: java.nio.file.Path, width: Int, height: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            check(ImageIO.write(image, "jpg", path.toFile()))
        }

        private fun writeGif(path: java.nio.file.Path, width: Int, height: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            check(ImageIO.write(image, "gif", path.toFile()))
        }
    }
}
