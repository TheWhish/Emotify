package me.whish.emotify.fabric.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FabricServerConfigCodecTest : FunSpec({
    test("empty config uses the safe enabled default") {
        FabricServerConfigCodec.decode("") shouldBe FabricServerConfigSnapshot()
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

        FabricServerConfigCodec.decode(FabricServerConfigCodec.encode(configured)) shouldBe configured
    }

    test("unknown duplicate and malformed values fail closed") {
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("unknown=true\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("customEmojis.enabled=true\ncustomEmojis.enabled=false\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("customEmojis.enabled=yes\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("customEmojis.enabled\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("broadcast.radiusBlocks=65\n")
        }
        shouldThrow<IllegalArgumentException> {
            FabricServerConfigCodec.decode("emotions.allow=emotify:happy\nemotions.deny=emotify:happy\n")
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
