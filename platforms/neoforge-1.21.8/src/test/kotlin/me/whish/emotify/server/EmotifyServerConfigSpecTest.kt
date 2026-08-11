package me.whish.emotify.server

import com.electronwill.nightconfig.core.CommentedConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotifyServerConfigSpecTest : FunSpec({
    test("empty NeoForge server configuration receives the complete audited defaults") {
        val config = CommentedConfig.inMemory()

        EmotifyServerConfig.spec.correct(config)

        config.get<Int>("configVersion") shouldBe 1
        config.get<Boolean>("enabled") shouldBe true
        config.get<Int>("cooldownMillis") shouldBe 3_000
        config.get<Boolean>("customEmojis.enabled") shouldBe true
        config.get<Int>("customEmojis.maximumStaticResolution") shouldBe 128
        config.get<Int>("customEmojis.maximumAnimatedResolution") shouldBe 64
        config.get<List<String>>("emotions.allow") shouldBe emptyList()
        config.get<List<String>>("emotions.deny") shouldBe emptyList()
        config.get<Double>("broadcast.radiusBlocks") shouldBe 64.0
        config.get<Int>("broadcast.maximumTrackingCandidates") shouldBe 256
        config.get<Int>("broadcast.globalBurstCapacity") shouldBe 512
        config.get<Int>("broadcast.globalRefillPerSecond") shouldBe 256
        config.get<Int>("broadcast.regionBurstCapacity") shouldBe 32
        config.get<Int>("broadcast.regionRefillPerSecond") shouldBe 16
        config.get<Int>("broadcast.maximumRegions") shouldBe 4_096
        config.get<Int>("ingress.maximumOutstandingSelections") shouldBe 512
        config.get<Int>("ingress.globalBurstCapacity") shouldBe 1_024
        config.get<Int>("ingress.globalRefillPerSecond") shouldBe 512
    }

    test("future NeoForge server schema remains opaque to automatic correction") {
        val config = CommentedConfig.inMemory()
        config.set<Int>("configVersion", 2)
        config.set<String>("enabled", "future-value")

        EmotifyServerConfig.spec.isCorrect(config) shouldBe true

        config.get<String>("enabled") shouldBe "future-value"
    }

    test("explicit legacy NeoForge schema is corrected once while valid values survive") {
        val config = CommentedConfig.inMemory()
        config.set<Int>("configVersion", 0)
        config.set<Boolean>("enabled", false)
        config.set<Int>("cooldownMillis", 2_200)

        EmotifyServerConfig.spec.isCorrect(config) shouldBe false
        EmotifyServerConfig.spec.correct(config)

        config.get<Int>("configVersion") shouldBe 1
        config.get<Boolean>("enabled") shouldBe false
        config.get<Int>("cooldownMillis") shouldBe 3_000
        EmotifyServerConfig.spec.isCorrect(config) shouldBe true
    }
})
