package me.whish.emotify.client

import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadataNode
import me.whish.emotify.client.custom.CustomEmojiExportFormat
import me.whish.emotify.client.custom.CustomEmojiExportPlan
import me.whish.emotify.client.custom.CustomEmojiFileScanner
import me.whish.emotify.client.custom.GifFrameTiming
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiPixels

sealed interface CustomEmojiExportResult {
    val path: Path

    data class Saved(override val path: Path) : CustomEmojiExportResult

    data class AlreadyExists(override val path: Path) : CustomEmojiExportResult

    data class TooLarge(override val path: Path) : CustomEmojiExportResult
}

object CustomEmojiAssetExporter {
    fun export(directory: Path, asset: CustomEmojiAsset, descriptor: CustomEmojiDescriptor): CustomEmojiExportResult {
        prepareDirectory(directory)
        val plan = CustomEmojiExportPlan.forAsset(asset, descriptor)
        val target = availableTarget(directory, plan.fileName)

        val temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
        return try {
            write(temporary, asset, descriptor, plan.format)
            if (Files.size(temporary) > CustomEmojiFileScanner.MAXIMUM_FILE_BYTES) {
                CustomEmojiExportResult.TooLarge(target)
            } else {
                moveNewFile(temporary, target)
                CustomEmojiExportResult.Saved(target)
            }
        } catch (_: FileAlreadyExistsException) {
            CustomEmojiExportResult.AlreadyExists(target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun availableTarget(directory: Path, fileName: String): Path {
        val separator = fileName.lastIndexOf('.')
        val stem = fileName.substring(0, separator)
        val extension = fileName.substring(separator)
        repeat(MAXIMUM_FILE_NAME_ATTEMPTS) { index ->
            val suffix = if (index == 0) "" else " (${index + 1})"
            val candidate = directory.resolve("$stem$suffix$extension")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw IllegalStateException("No available custom emoji file name for $fileName")
    }

    private fun prepareDirectory(directory: Path) {
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Custom emoji directory cannot be a symbolic link: $directory" }
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Custom emoji path is not a directory: $directory"
        }
    }

    private fun write(
        path: Path,
        asset: CustomEmojiAsset,
        descriptor: CustomEmojiDescriptor,
        format: CustomEmojiExportFormat,
    ) {
        when (format) {
            CustomEmojiExportFormat.PNG -> writePng(path, asset, descriptor)
            CustomEmojiExportFormat.GIF -> writeGif(path, asset, descriptor)
        }
    }

    private fun writePng(path: Path, asset: CustomEmojiAsset, descriptor: CustomEmojiDescriptor) {
        val image = image(asset.pixels)
        val writer = imageWriter("png")
        try {
            Files.newOutputStream(path).use { bytes ->
                ImageIO.createImageOutputStream(bytes).use { output ->
                    writer.output = output
                    val parameters = writer.defaultWriteParam
                    val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), parameters)
                    val formatName = metadata.nativeMetadataFormatName
                    val root = metadata.getAsTree(formatName) as IIOMetadataNode
                    CustomEmojiEmbeddedDescriptor.addPng(root, descriptor)
                    metadata.setFromTree(formatName, root)
                    writer.write(null, IIOImage(image, null, metadata), parameters)
                }
            }
        } finally {
            writer.dispose()
        }
    }

    private fun writeGif(path: Path, asset: CustomEmojiAsset, descriptor: CustomEmojiDescriptor) {
        val writer = imageWriter("gif")
        try {
            Files.newOutputStream(path).use { bytes ->
                ImageIO.createImageOutputStream(bytes).use { output ->
                    writer.output = output
                    writer.prepareWriteSequence(null)
                    try {
                        val delays = GifFrameTiming.quantizeToCentiseconds(
                            asset.frames.map { frame -> frame.durationMillis },
                        )
                        asset.frames.forEachIndexed { index, frame ->
                            val image = image(frame.pixels)
                            val parameters = writer.defaultWriteParam
                            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), parameters)
                            val formatName = metadata.nativeMetadataFormatName
                            val root = metadata.getAsTree(formatName) as IIOMetadataNode
                            val control = root.node("GraphicControlExtension")
                            control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                            control.setAttribute("userInputFlag", "FALSE")
                            control.setAttribute("transparentColorFlag", if (frame.pixels.hasTransparency()) "TRUE" else "FALSE")
                            control.setAttribute("delayTime", delays[index].toString())
                            control.setAttribute("transparentColorIndex", "0")
                            if (index == 0) {
                                root.addLoopExtension()
                                CustomEmojiEmbeddedDescriptor.addGif(root, descriptor)
                            }
                            metadata.setFromTree(formatName, root)
                            writer.writeToSequence(IIOImage(image, null, metadata), parameters)
                        }
                    } finally {
                        writer.endWriteSequence()
                    }
                }
            }
        } finally {
            writer.dispose()
        }
    }

    private fun moveNewFile(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target)
        }
    }

    private fun image(pixels: CustomEmojiPixels): BufferedImage {
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        repeat(pixels.pixelCount) { index ->
            val color = pixels.colorAt(index)
            val red = color and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color ushr 16 and 0xFF
            val alpha = color ushr 24 and 0xFF
            image.setRGB(
                index % pixels.width,
                index / pixels.width,
                alpha shl 24 or (red shl 16) or (green shl 8) or blue,
            )
        }
        return image
    }

    private fun CustomEmojiPixels.hasTransparency(): Boolean {
        repeat(pixelCount) { index ->
            if (colorAt(index) ushr 24 != 0xFF) {
                return true
            }
        }
        return false
    }

    private fun imageWriter(format: String): ImageWriter {
        val writers = ImageIO.getImageWritersByFormatName(format)
        check(writers.hasNext()) { "${format.uppercase()} image writer is unavailable" }
        return writers.next()
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

    private fun IIOMetadataNode.addLoopExtension() {
        val extensions = node("ApplicationExtensions")
        val extension = IIOMetadataNode("ApplicationExtension")
        extension.setAttribute("applicationID", "NETSCAPE")
        extension.setAttribute("authenticationCode", "2.0")
        extension.userObject = byteArrayOf(1, 0, 0)
        extensions.appendChild(extension)
    }

    private const val TEMPORARY_PREFIX = ".emotify-shared-"
    private const val TEMPORARY_SUFFIX = ".tmp"
    private const val MAXIMUM_FILE_NAME_ATTEMPTS = CustomEmojiFileScanner.MAXIMUM_FILES
}


