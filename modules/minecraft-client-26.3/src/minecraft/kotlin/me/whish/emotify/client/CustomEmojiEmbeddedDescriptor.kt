package me.whish.emotify.client

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import me.whish.emotify.client.custom.CustomEmojiEmbeddedDescriptorCodec
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.domain.CustomEmojiDescriptor

object CustomEmojiEmbeddedDescriptor {
    fun read(format: CustomEmojiFileFormat, bytes: ByteArray): CustomEmojiDescriptor? = when (format) {
        CustomEmojiFileFormat.PNG -> readPng(bytes)
        CustomEmojiFileFormat.GIF -> readGif(bytes)
        CustomEmojiFileFormat.JPEG -> null
    }

    fun addPng(root: IIOMetadataNode, descriptor: CustomEmojiDescriptor) {
        val text = root.node("tEXt")
        val entry = IIOMetadataNode("tEXtEntry")
        entry.setAttribute("keyword", PNG_KEYWORD)
        entry.setAttribute("value", CustomEmojiEmbeddedDescriptorCodec.encode(descriptor))
        text.appendChild(entry)
    }

    fun addGif(root: IIOMetadataNode, descriptor: CustomEmojiDescriptor) {
        val extensions = root.node("CommentExtensions")
        val extension = IIOMetadataNode("CommentExtension")
        extension.setAttribute("value", GIF_PREFIX + CustomEmojiEmbeddedDescriptorCodec.encode(descriptor))
        extensions.appendChild(extension)
    }

    private fun readPng(bytes: ByteArray): CustomEmojiDescriptor? {
        if (!bytes.startsWith(PNG_SIGNATURE)) {
            return null
        }
        var position = PNG_SIGNATURE_BYTES
        while (position.toLong() + PNG_CHUNK_OVERHEAD <= bytes.size) {
            val length = bytes.intBigEndian(position)
            val nextPosition = position.toLong() + PNG_CHUNK_OVERHEAD + length
            if (length < 0 || nextPosition > bytes.size) {
                return null
            }
            val typeOffset = position + Int.SIZE_BYTES
            val dataOffset = typeOffset + PNG_TYPE_BYTES
            if (bytes.matchesAscii(typeOffset, PNG_END_CHUNK)) {
                return null
            }
            if (bytes.matchesAscii(typeOffset, PNG_TEXT_CHUNK)) {
                val separator = bytes.indexOf(0, dataOffset, dataOffset + length)
                if (separator >= 0 && bytes.matchesAscii(dataOffset, PNG_KEYWORD) && separator == dataOffset + PNG_KEYWORD.length) {
                    return CustomEmojiEmbeddedDescriptorCodec.decode(
                        String(bytes, separator + 1, dataOffset + length - separator - 1, StandardCharsets.ISO_8859_1),
                    )
                }
            }
            position = nextPosition.toInt()
        }
        return null
    }

    private fun readGif(bytes: ByteArray): CustomEmojiDescriptor? {
        ByteArrayInputStream(bytes).use { source ->
            ImageIO.createImageInputStream(source).use { input ->
                val readers = ImageIO.getImageReadersByFormatName("gif")
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    val metadata = reader.getImageMetadata(0)
                    val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
                    val extensions = root.getElementsByTagName("CommentExtension")
                    for (index in 0 until extensions.length) {
                        val extension = extensions.item(index) as IIOMetadataNode
                        val value = extension.getAttribute("value")
                        if (value.startsWith(GIF_PREFIX)) {
                            return CustomEmojiEmbeddedDescriptorCodec.decode(value.substring(GIF_PREFIX.length))
                        }
                    }
                    return null
                } finally {
                    reader.dispose()
                }
            }
        }
    }

    private fun ByteArray.intBigEndian(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset <= size - value.length && value.indices.all { index -> this[offset + index].toInt() == value[index].code }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.indexOf(value: Int, start: Int, end: Int): Int {
        for (index in start until end.coerceAtMost(size)) {
            if (this[index].toInt() and 0xFF == value) return index
        }
        return -1
    }

    private fun IIOMetadataNode.node(name: String): IIOMetadataNode {
        var child = firstChild
        while (child != null) {
            if (child.nodeName == name) return child as IIOMetadataNode
            child = child.nextSibling
        }
        return IIOMetadataNode(name).also(::appendChild)
    }

    private const val PNG_SIGNATURE_BYTES = 8
    private const val PNG_TYPE_BYTES = 4
    private const val PNG_CHUNK_OVERHEAD = 12
    private const val PNG_TEXT_CHUNK = "tEXt"
    private const val PNG_END_CHUNK = "IEND"
    private const val PNG_KEYWORD = "emotify"
    private const val GIF_PREFIX = "emotify:"
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
}

