package me.whish.emotify.client.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Suppress("unused")
class ClientConfigurationFileIOTest : FunSpec({
    lateinit var directory: Path

    beforeTest {
        directory = Files.createTempDirectory("emotify-config-test")
    }

    afterTest {
        directory.toFile().deleteRecursively()
    }

    test("migration backup is exact bounded and never overwritten") {
        val source = directory.resolve("emotify-client.properties")
        val backup = directory.resolve("emotify-client.properties.v0.bak")
        Files.writeString(source, "legacy=true\n", StandardCharsets.UTF_8)

        ClientConfigurationFileIO.createBackupIfAbsent(source, backup, 64) shouldBe true
        Files.readString(backup, StandardCharsets.UTF_8) shouldBe "legacy=true\n"

        ClientConfigurationFileIO.createBackupIfAbsent(source, backup, 64) shouldBe false

        Files.writeString(source, "changed=true\n", StandardCharsets.UTF_8)
        shouldThrow<IllegalArgumentException> {
            ClientConfigurationFileIO.createBackupIfAbsent(source, backup, 64)
        }
        Files.readString(backup, StandardCharsets.UTF_8) shouldBe "legacy=true\n"
        Files.exists(directory.resolve("emotify-client.properties.v0.bak.tmp")) shouldBe false
    }

    test("migration rejects a non-file backup target") {
        val source = directory.resolve("emotify-client.properties")
        val backup = directory.resolve("emotify-client.properties.v0.bak")
        Files.writeString(source, "legacy=true\n", StandardCharsets.UTF_8)
        Files.createDirectory(backup)

        shouldThrow<IllegalArgumentException> {
            ClientConfigurationFileIO.createBackupIfAbsent(source, backup, 64)
        }
    }

    test("atomic UTF eight persistence replaces the target without leaving a temporary file") {
        val target = directory.resolve("emotify-client.properties")
        Files.writeString(target, "old", StandardCharsets.UTF_8)

        ClientConfigurationFileIO.writeUtf8Atomically(target, "новый")

        ClientConfigurationFileIO.readUtf8(target, 64) shouldBe "новый"
        Files.exists(directory.resolve("emotify-client.properties.tmp")) shouldBe false
    }

    test("bounded UTF eight reads reject oversized and malformed files") {
        val oversized = directory.resolve("oversized.properties")
        Files.write(oversized, ByteArray(9) { 'a'.code.toByte() })
        shouldThrow<IllegalArgumentException> {
            ClientConfigurationFileIO.readUtf8(oversized, 8)
        }

        val malformed = directory.resolve("malformed.properties")
        Files.write(malformed, byteArrayOf(0xC3.toByte(), 0x28))
        shouldThrow<IllegalArgumentException> {
            ClientConfigurationFileIO.readUtf8(malformed, 8)
        }
    }
})
