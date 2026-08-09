package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Suppress("unused")
class ServerConfigurationFileIOTest : FunSpec({
    test("legacy backup is exact bounded and idempotent") {
        val directory = Files.createTempDirectory("emotify-server-config-")
        try {
            val source = directory.resolve("config.yml")
            val backup = directory.resolve("config.yml.v0.bak")
            Files.writeString(source, "enabled: false\n", StandardCharsets.UTF_8)

            ServerConfigurationFileIO.createBackupIfAbsent(source, backup, 64) shouldBe true
            ServerConfigurationFileIO.createBackupIfAbsent(source, backup, 64) shouldBe false

            Files.readAllBytes(backup).contentEquals(Files.readAllBytes(source)) shouldBe true
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("mismatched backup blocks migration without changing either file") {
        val directory = Files.createTempDirectory("emotify-server-config-")
        try {
            val source = directory.resolve("config.yml")
            val backup = directory.resolve("config.yml.v0.bak")
            Files.writeString(source, "enabled: false\n", StandardCharsets.UTF_8)
            Files.writeString(backup, "enabled: true\n", StandardCharsets.UTF_8)

            shouldThrow<IllegalArgumentException> {
                ServerConfigurationFileIO.createBackupIfAbsent(source, backup, 64)
            }

            Files.readString(source, StandardCharsets.UTF_8) shouldBe "enabled: false\n"
            Files.readString(backup, StandardCharsets.UTF_8) shouldBe "enabled: true\n"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("atomic write does not follow a deterministic temporary symlink") {
        val directory = Files.createTempDirectory("emotify-server-config-")
        try {
            val external = directory.resolve("external.txt")
            val target = directory.resolve("emotify-server.properties")
            val temporary = directory.resolve("emotify-server.properties.tmp")
            Files.writeString(external, "untouched", StandardCharsets.UTF_8)
            Files.createLink(temporary, external)

            ServerConfigurationFileIO.writeUtf8Atomically(target, "configVersion=1\n")

            Files.readString(target, StandardCharsets.UTF_8) shouldBe "configVersion=1\n"
            Files.readString(external, StandardCharsets.UTF_8) shouldBe "untouched"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})
