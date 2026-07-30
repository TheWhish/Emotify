package me.whish.emotify.wire.v1

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

@Suppress("unused")
class ProtocolV1BoundaryTest : FunSpec({
    test("pure Protocol 1 sources do not import platform or resource libraries") {
        val projectDirectory = generateSequence(Path.of("").toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve("settings.gradle")) }
        val root = projectDirectory.resolve("modules/emotify-wire-v1/src/main/kotlin")
        val forbiddenImports = listOf(
            "net.minecraft",
            "net.neoforged",
            "io.netty",
            "com.google.gson",
            "org.bukkit",
            "net.fabricmc",
            "thedarkcolour",
        )
        val violations = Files.walk(root).use { paths ->
            paths
                .filter { path -> path.extension == "kt" }
                .flatMap { path ->
                    forbiddenImports
                        .filter(path.readText()::contains)
                        .map { forbidden -> "$path imports $forbidden" }
                        .stream()
                }
                .toList()
        }

        violations.shouldBeEmpty()
    }
})
