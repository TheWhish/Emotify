package me.whish.emotify.client.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object ClientConfigurationFileIO {
    fun readUtf8(path: Path, maximumBytes: Int): String {
        val bytes = readBytes(path, maximumBytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Emotify client config is not valid UTF-8", error)
        }
    }

    fun createBackupIfAbsent(source: Path, backup: Path, maximumBytes: Int): Boolean {
        validateMaximumBytes(maximumBytes)
        val bytes = readBytes(source, maximumBytes)
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                "Emotify config backup is not a regular file: $backup"
            }
            require(readBytes(backup, maximumBytes).contentEquals(bytes)) {
                "Emotify config backup does not match the current legacy file: $backup"
            }
            return false
        }
        val parent = requireNotNull(backup.parent) { "Emotify config backup path has no parent: $backup" }
        Files.createDirectories(parent)
        val temporary = parent.resolve("${backup.fileName}.tmp")
        return try {
            Files.deleteIfExists(temporary)
            Files.write(
                temporary,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            moveWithoutReplacement(temporary, backup)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun writeUtf8Atomically(target: Path, content: String) {
        val parent = requireNotNull(target.parent) { "Emotify config path has no parent: $target" }
        Files.createDirectories(parent)
        val temporary = parent.resolve("${target.fileName}.tmp")
        try {
            Files.deleteIfExists(temporary)
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readBytes(path: Path, maximumBytes: Int): ByteArray {
        validateMaximumBytes(maximumBytes)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Emotify client config is not a regular file: $path"
        }
        val bytes = Files.newInputStream(path).use { input -> input.readNBytes(maximumBytes + 1) }
        require(bytes.size <= maximumBytes) { "Emotify client config exceeds $maximumBytes bytes" }
        return bytes
    }

    private fun validateMaximumBytes(maximumBytes: Int) {
        require(maximumBytes in 1 until Int.MAX_VALUE) {
            "Maximum Emotify client config size is outside safe limits: $maximumBytes"
        }
    }

    private fun moveWithoutReplacement(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
