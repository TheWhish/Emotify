package me.whish.emotify.paper.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog

@Suppress("unused")
class BukkitPaperConfigLoaderTest : FunSpec({
    val catalog = BuiltInEmotionCatalog.catalog

    fun load(bytes: ByteArray): PaperConfigLoadResult {
        val directory = Files.createTempDirectory("emotify-config-test")
        val config = directory.resolve("config.yml")
        return try {
            Files.write(config, bytes)
            BukkitPaperConfigLoader(config.toFile(), catalog).load()
        } finally {
            Files.deleteIfExists(config)
            Files.deleteIfExists(directory)
        }
    }

    fun load(source: String): PaperConfigLoadResult = load(source.toByteArray(Charsets.UTF_8))

    test("strict YAML loads the shipped configuration shape") {
        val result = load(
            """
            config-version: 1
            enabled: true
            cooldown-millis: 2200
            emotions:
              allow: []
              deny: []
            broadcast:
              radius-blocks: 48.0
              maximum-tracking-candidates: 128
            ingress:
              maximum-queued-main-thread-tasks: 256
            """.trimIndent(),
        ).shouldBeInstanceOf<PaperConfigLoadResult.Loaded>()

        result.config.broadcast.audience.radius shouldBe 48.0
        result.config.broadcast.audience.maximumTrackingCandidates shouldBe 128
        result.config.ingress.maximumQueuedMainThreadTasks shouldBe 256
    }

    test("oversized and malformed UTF-8 inputs fail before YAML construction") {
        val oversized = load(ByteArray(65_537) { 'a'.code.toByte() })
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()
        val malformed = load(byteArrayOf(0xC3.toByte(), 0x28))
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()

        oversized.violations.single() shouldContain "byte limit"
        malformed.violations.single() shouldContain "valid UTF-8"
    }

    test("duplicate keys anchors and excessive nesting are rejected") {
        val duplicate = load("enabled: true\nenabled: false\n")
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()
        val anchored = load("enabled: &value true\ncopy: *value\n")
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()
        val nested = load("a:\n  b:\n    c:\n      d:\n        e: true\n")
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()

        duplicate.violations.single() shouldContain "Invalid YAML"
        anchored.violations.single() shouldContain "anchors and aliases"
        nested.violations.single() shouldContain "Invalid YAML"
    }

    test("empty unknown sections nulls and oversized filters fail closed") {
        val emptySection = load("external: {}\n")
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()
        val nullValue = load("enabled: null\n")
            .shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()
        val oversizedFilter = load(
            buildString {
                appendLine("emotions:")
                appendLine("  allow:")
                repeat(65) { appendLine("    - emotify:heart") }
            },
        ).shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()

        emptySection.violations.single() shouldContain "cannot be empty"
        nullValue.violations.single() shouldContain "cannot be null"
        oversizedFilter.violations.single() shouldContain "more than 64"
    }

    test("diagnostics have bounded count and message length") {
        val source = buildString {
            repeat(64) { index -> appendLine("unknown-$index: true") }
        }
        val result = load(source).shouldBeInstanceOf<PaperConfigLoadResult.Invalid>()

        result.violations shouldHaveSize 32
        result.violations.all { violation -> violation.length <= 256 } shouldBe true
    }

    test("missing file is reported as an operational read failure") {
        val missing = Path.of("build", "missing-emotify-config-${System.nanoTime()}.yml")

        BukkitPaperConfigLoader(missing.toFile(), catalog).load()
            .shouldBeInstanceOf<PaperConfigLoadResult.Failed>()
    }
})
