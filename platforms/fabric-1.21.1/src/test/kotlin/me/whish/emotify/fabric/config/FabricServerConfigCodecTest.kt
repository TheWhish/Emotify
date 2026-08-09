package me.whish.emotify.fabric.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FabricServerConfigCodecTest : FunSpec({
    test("legacy config uses defaults and requests migration") {
        val result = FabricServerConfigCodec.decode("")
            .shouldBeInstanceOf<FabricServerConfigDecodeResult.Ready>()
        val defaults = result.snapshot

        defaults shouldBe FabricServerConfigSnapshot()
        defaults.cooldownMillis shouldBe 3_000
        result.version shouldBe me.whish.emotify.server.core.ServerConfigurationVersion.Legacy
    }

    test("complete server settings round trip without losing operational limits") {
        val configured = FabricServerConfigSnapshot(
            enabled = false,
            customEmojisEnabled = false,
            maximumStaticCustomEmojiSize = 64,
            maximumAnimatedCustomEmojiSize = 32,
            cooldownMillis = 4_000,
            allowedEmotionIds = setOf(EmotionId.of("emotify:happy")),
            deniedEmotionIds = setOf(EmotionId.of("emotify:sad")),
            broadcastRadiusBlocks = 32.0,
            maximumTrackingCandidates = 64,
            broadcastGlobalBurstCapacity = 128,
            broadcastGlobalRefillPerSecond = 64,
            broadcastRegionBurstCapacity = 8,
            broadcastRegionRefillPerSecond = 4,
            maximumBroadcastRegions = 512,
            maximumOutstandingSelections = 128,
            selectionGlobalBurstCapacity = 256,
            selectionGlobalRefillPerSecond = 128,
        )

        FabricServerConfigCodec.encode(configured) shouldStartWith "configVersion=1\n"
        val decoded = FabricServerConfigCodec.decode(FabricServerConfigCodec.encode(configured))
            .shouldBeInstanceOf<FabricServerConfigDecodeResult.Ready>()

        decoded.snapshot shouldBe configured
        decoded.version shouldBe me.whish.emotify.server.core.ServerConfigurationVersion.Current
    }

    test("future schema is opaque and does not parse incompatible values") {
        FabricServerConfigCodec.decode(
            "configVersion=2\nfuture.value=opaque\nenabled=not-a-boolean\n",
        ) shouldBe FabricServerConfigDecodeResult.Future(2)
    }

    test("legacy storage migration creates one exact backup and current snapshot") {
        val directory = Files.createTempDirectory("emotify-fabric-config-")
        try {
            val config = directory.resolve("emotify-server.properties")
            val legacy = "enabled=false\ncooldownMillis=3000\n"
            Files.writeString(config, legacy, StandardCharsets.UTF_8)

            val loaded = FabricServerConfigStorage.load(config)

            loaded.enabled shouldBe false
            Files.readString(config, StandardCharsets.UTF_8) shouldStartWith "configVersion=1\n"
            Files.readString(
                directory.resolve("emotify-server.properties.v0.bak"),
                StandardCharsets.UTF_8,
            ) shouldBe legacy
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("future storage config disables runtime without rewriting source") {
        val directory = Files.createTempDirectory("emotify-fabric-config-")
        try {
            val config = directory.resolve("emotify-server.properties")
            val source = "configVersion=2\nenabled=incompatible\nfuture.value=opaque\n"
            Files.writeString(config, source, StandardCharsets.UTF_8)

            val loaded = FabricServerConfigStorage.load(config)

            loaded.enabled shouldBe false
            loaded.customEmojisEnabled shouldBe false
            Files.readString(config, StandardCharsets.UTF_8) shouldBe source
            Files.exists(directory.resolve("emotify-server.properties.v0.bak")) shouldBe false
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("cooldown below the complete animation lifecycle fails closed") {
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("cooldownMillis=2999\n")
        }
    }

    test("unknown duplicate and malformed values fail closed") {
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("unknown=true\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("customEmojis.enabled=true\ncustomEmojis.enabled=false\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("customEmojis.enabled=yes\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("customEmojis.enabled\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("broadcast.radiusBlocks=65\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decodeCompatible("emotions.allow=emotify:happy\nemotions.deny=emotify:happy\n")
        }
    }

    test("config persistence ignores a pre-existing deterministic temporary path") {
        val directory = Files.createTempDirectory("emotify-fabric-config-")
        try {
            val external = directory.resolve("external.txt")
            val config = directory.resolve("emotify-server.properties")
            val deterministicTemporary = directory.resolve("emotify-server.properties.tmp")
            Files.writeString(external, "untouched", StandardCharsets.UTF_8)
            Files.createLink(deterministicTemporary, external)

            FabricServerConfigPersistence.write(config, "enabled=true\n")

            Files.readString(config, StandardCharsets.UTF_8) shouldBe "enabled=true\n"
            Files.readString(external, StandardCharsets.UTF_8) shouldBe "untouched"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})
