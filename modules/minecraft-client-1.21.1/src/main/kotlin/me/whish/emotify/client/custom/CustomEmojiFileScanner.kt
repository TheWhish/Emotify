package me.whish.emotify.client.custom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.Locale
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiDisplayNamePolicy
import me.whish.emotify.domain.CustomEmojiPixels

enum class CustomEmojiFileFormat {
    PNG,
    JPEG,
    GIF,
}

data class CustomEmojiFile(
    val path: Path,
    val displayName: String,
    val sourceSize: Int,
    val format: CustomEmojiFileFormat,
)

enum class CustomEmojiFileRejectionReason {
    INVALID_IMAGE,
    UNSUPPORTED_DIMENSIONS,
    FILE_TOO_LARGE,
}

data class CustomEmojiFileRejection(
    val path: Path,
    val displayName: String,
    val format: CustomEmojiFileFormat,
    val reason: CustomEmojiFileRejectionReason,
)

data class CustomEmojiDirectoryEntry(
    val fileName: String,
    val size: Long,
    val lastModifiedMillis: Long,
)

data class CustomEmojiDirectoryFingerprint(
    val entries: List<CustomEmojiDirectoryEntry>,
    val directoryLimitReached: Boolean,
) {
    companion object {
        val EMPTY = CustomEmojiDirectoryFingerprint(emptyList(), false)
    }
}

data class CustomEmojiFileScan(
    val accepted: List<CustomEmojiFile>,
    val rejected: List<CustomEmojiFileRejection>,
    val fingerprint: CustomEmojiDirectoryFingerprint,
) {
    companion object {
        val EMPTY = CustomEmojiFileScan(emptyList(), emptyList(), CustomEmojiDirectoryFingerprint.EMPTY)
    }
}

object CustomEmojiFileScanner {
    const val MAXIMUM_FILES = 128
    const val MAXIMUM_FILE_BYTES = 512 * 1_024

    private const val MAXIMUM_DIRECTORY_ENTRIES = 512
    private const val PNG_HEADER_BYTES = 24
    private const val JPEG_START_OF_IMAGE = 0xD8
    private const val JPEG_END_OF_IMAGE = 0xD9
    private const val JPEG_START_OF_SCAN = 0xDA
    private const val GIF_HEADER_BYTES = 10
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
    private val JPEG_DIMENSION_MARKERS = setOf(
        0xC0,
        0xC1,
        0xC2,
        0xC3,
        0xC5,
        0xC6,
        0xC7,
        0xC9,
        0xCA,
        0xCB,
        0xCD,
        0xCE,
        0xCF,
    )

    fun scan(directory: Path, previous: CustomEmojiFileScan = CustomEmojiFileScan.EMPTY): CustomEmojiFileScan {
        require(previous.accepted.size + previous.rejected.size <= MAXIMUM_FILES) {
            "Previous custom emoji scan exceeds the file limit"
        }
        require(previous.fingerprint.entries.size <= MAXIMUM_FILES) {
            "Previous custom emoji fingerprint exceeds the file limit"
        }
        val candidates = candidates(directory)
        val previousInspections = previous.inspectionsByPath()
        val accepted = ArrayList<CustomEmojiFile>(candidates.entries.size)
        val rejected = ArrayList<CustomEmojiFileRejection>()
        candidates.entries.forEach { candidate ->
            val inspection = previousInspections[candidate.path.normalize()]
                ?.takeIf { cached -> cached.fingerprint == candidate.fingerprint }
                ?.inspection
                ?: try {
                    inspect(candidate.path)
                } catch (_: Exception) {
                    Inspection.rejected(
                        candidate.path,
                        supportedFormat(candidate.path),
                        CustomEmojiFileRejectionReason.INVALID_IMAGE,
                    )
                }
            inspection.fold(accepted::add, rejected::add)
        }
        return CustomEmojiFileScan(
            java.util.List.copyOf(accepted),
            java.util.List.copyOf(rejected),
            candidates.fingerprint,
        )
    }

    fun fingerprint(directory: Path): CustomEmojiDirectoryFingerprint = candidates(directory).fingerprint

    fun matchesExpectedImage(file: CustomEmojiFile, bytes: ByteArray): Boolean =
        dimensions(file.format, bytes) == (file.sourceSize to file.sourceSize)

    private fun candidates(directory: Path): Candidates {
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Custom emoji path must be a directory: $directory"
        }

        val entries = ArrayList<Path>(MAXIMUM_DIRECTORY_ENTRIES)
        var directoryLimitReached = false
        Files.newDirectoryStream(directory).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (entries.size == MAXIMUM_DIRECTORY_ENTRIES) {
                    directoryLimitReached = true
                    break
                }
                entries.add(iterator.next())
            }
        }

        val supported = entries.asSequence()
            .filter(::isSupportedImageFile)
            .sortedWith(compareBy<Path>({ it.fileName.toString().lowercase(Locale.ROOT) }, { it.fileName.toString() }))
            .toList()
        if (supported.size > MAXIMUM_FILES) {
            directoryLimitReached = true
        }
        val bounded = supported.take(MAXIMUM_FILES).map { path ->
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            Candidate(
                path,
                CustomEmojiDirectoryEntry(
                    path.fileName.toString(),
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                ),
            )
        }
        return Candidates(
            bounded,
            CustomEmojiDirectoryFingerprint(
                java.util.List.copyOf(bounded.map(Candidate::fingerprint)),
                directoryLimitReached,
            ),
        )
    }

    private fun CustomEmojiFileScan.inspectionsByPath(): Map<Path, CachedInspection> {
        if (this == CustomEmojiFileScan.EMPTY) {
            return emptyMap()
        }
        val fingerprintsByName = fingerprint.entries.associateBy(CustomEmojiDirectoryEntry::fileName)
        val inspections = LinkedHashMap<Path, CachedInspection>(accepted.size + rejected.size)
        accepted.forEach { file ->
            fingerprintsByName[file.path.fileName.toString()]?.let { entry ->
                inspections[file.path.normalize()] = CachedInspection(entry, Inspection.accepted(file))
            }
        }
        rejected.forEach { rejection ->
            fingerprintsByName[rejection.path.fileName.toString()]?.let { entry ->
                inspections[rejection.path.normalize()] = CachedInspection(entry, Inspection.Rejected(rejection))
            }
        }
        return inspections
    }

    private fun isSupportedImageFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && format(path) != null

    private fun inspect(path: Path): Inspection<CustomEmojiFile, CustomEmojiFileRejection> {
        val format = supportedFormat(path)
        val size = Files.size(path)
        if (size > MAXIMUM_FILE_BYTES) {
            return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.FILE_TOO_LARGE)
        }
        if (size <= 0L) {
            return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.INVALID_IMAGE)
        }

        val bytes = Files.newInputStream(path).use { input ->
            input.readNBytes(MAXIMUM_FILE_BYTES + 1)
        }
        if (bytes.size > MAXIMUM_FILE_BYTES) {
            return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.FILE_TOO_LARGE)
        }
        val dimensions = dimensions(format, bytes)
            ?: return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.INVALID_IMAGE)
        if (
            dimensions.first != dimensions.second ||
            !CustomEmojiPixels.supports(dimensions.first) ||
            format == CustomEmojiFileFormat.GIF && dimensions.first > CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE
        ) {
            return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.UNSUPPORTED_DIMENSIONS)
        }

        val fileName = path.fileName.toString()
        val displayName = fileName.substring(0, fileName.lastIndexOf('.')).trim()
        if (displayName.isEmpty()) {
            return Inspection.rejected(path, format, CustomEmojiFileRejectionReason.INVALID_IMAGE)
        }
        return Inspection.accepted(
            CustomEmojiFile(
                path,
                descriptorSafeDisplayName(displayName),
                dimensions.first,
                format,
            ),
        )
    }

    private fun descriptorSafeDisplayName(value: String): String {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        val result = StringBuilder(minOf(normalized.length, CustomEmojiDescriptor.MAXIMUM_DISPLAY_NAME_LENGTH))
        var utf8Bytes = 0
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val characterCount = Character.charCount(codePoint)
            offset += characterCount
            if (!CustomEmojiDisplayNamePolicy.isSafeCodePoint(codePoint)) {
                continue
            }
            if (result.length + characterCount > CustomEmojiDescriptor.MAXIMUM_DISPLAY_NAME_LENGTH) {
                break
            }
            val characters = String(Character.toChars(codePoint))
            val characterBytes = characters.toByteArray(StandardCharsets.UTF_8).size
            if (utf8Bytes + characterBytes > CustomEmojiDescriptor.MAXIMUM_DISPLAY_NAME_UTF8_BYTES) {
                break
            }
            result.append(characters)
            utf8Bytes += characterBytes
        }
        return result.toString().trim().ifEmpty { CustomEmojiDescriptor.DEFAULT_DISPLAY_NAME }
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < PNG_HEADER_BYTES || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes, 0, PNG_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452) {
            return null
        }
        val width = buffer.getInt(16)
        val height = buffer.getInt(20)
        return width to height
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || unsigned(bytes[0]) != 0xFF || unsigned(bytes[1]) != JPEG_START_OF_IMAGE) {
            return null
        }
        var position = 2
        while (position < bytes.size) {
            while (position < bytes.size && unsigned(bytes[position]) != 0xFF) {
                position++
            }
            while (position < bytes.size && unsigned(bytes[position]) == 0xFF) {
                position++
            }
            if (position >= bytes.size) {
                return null
            }
            val marker = unsigned(bytes[position++])
            if (marker == JPEG_END_OF_IMAGE || marker == JPEG_START_OF_SCAN) {
                return null
            }
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) {
                continue
            }
            if (position + 2 > bytes.size) {
                return null
            }
            val segmentLength = unsigned(bytes[position]) shl 8 or unsigned(bytes[position + 1])
            position += 2
            if (segmentLength < 2 || position + segmentLength - 2 > bytes.size) {
                return null
            }
            if (marker in JPEG_DIMENSION_MARKERS) {
                if (segmentLength < 7) {
                    return null
                }
                val height = unsigned(bytes[position + 1]) shl 8 or unsigned(bytes[position + 2])
                val width = unsigned(bytes[position + 3]) shl 8 or unsigned(bytes[position + 4])
                return width to height
            }
            position += segmentLength - 2
        }
        return null
    }

    private fun gifDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < GIF_HEADER_BYTES) {
            return null
        }
        val signature = String(bytes, 0, 6, Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") {
            return null
        }
        val width = unsigned(bytes[6]) or (unsigned(bytes[7]) shl 8)
        val height = unsigned(bytes[8]) or (unsigned(bytes[9]) shl 8)
        return width to height
    }

    private fun dimensions(format: CustomEmojiFileFormat, bytes: ByteArray): Pair<Int, Int>? = when (format) {
        CustomEmojiFileFormat.PNG -> pngDimensions(bytes)
        CustomEmojiFileFormat.JPEG -> jpegDimensions(bytes)
        CustomEmojiFileFormat.GIF -> gifDimensions(bytes)
    }

    private fun format(path: Path): CustomEmojiFileFormat? =
        when (path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> CustomEmojiFileFormat.PNG
            "jpg", "jpeg" -> CustomEmojiFileFormat.JPEG
            "gif" -> CustomEmojiFileFormat.GIF
            else -> null
        }

    private fun supportedFormat(path: Path): CustomEmojiFileFormat =
        checkNotNull(format(path)) { "Unsupported custom emoji file reached inspection: $path" }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private data class Candidates(
        val entries: List<Candidate>,
        val fingerprint: CustomEmojiDirectoryFingerprint,
    )

    private data class Candidate(
        val path: Path,
        val fingerprint: CustomEmojiDirectoryEntry,
    )

    private data class CachedInspection(
        val fingerprint: CustomEmojiDirectoryEntry,
        val inspection: Inspection<CustomEmojiFile, CustomEmojiFileRejection>,
    )

    private sealed interface Inspection<out A, out R> {
        fun fold(accept: (A) -> Unit, reject: (R) -> Unit)

        data class Accepted<A>(val value: A) : Inspection<A, Nothing> {
            override fun fold(accept: (A) -> Unit, reject: (Nothing) -> Unit) = accept(value)
        }

        data class Rejected<R>(val value: R) : Inspection<Nothing, R> {
            override fun fold(accept: (Nothing) -> Unit, reject: (R) -> Unit) = reject(value)
        }

        companion object {
            fun accepted(value: CustomEmojiFile): Inspection<CustomEmojiFile, CustomEmojiFileRejection> = Accepted(value)

            fun rejected(
                path: Path,
                format: CustomEmojiFileFormat,
                reason: CustomEmojiFileRejectionReason,
            ): Inspection<CustomEmojiFile, CustomEmojiFileRejection> =
                Rejected(
                    CustomEmojiFileRejection(
                        path,
                        descriptorSafeDisplayName(path.fileName.toString().substringBeforeLast('.')),
                        format,
                        reason,
                    ),
                )
        }
    }

}
