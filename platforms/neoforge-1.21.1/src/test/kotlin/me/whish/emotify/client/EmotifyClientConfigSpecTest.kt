package me.whish.emotify.client

import com.electronwill.nightconfig.core.CommentedConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotifyClientConfigSpecTest : FunSpec({
    test("empty favorites remain valid across NeoForge config correction") {
        val config = CommentedConfig.inMemory()
        config.set<Boolean>("showOtherPlayersEmotions", true)
        config.set<Boolean>("showCustomEmotions", false)
        config.set<Boolean>("reducedMotion", false)
        config.set<Int>("soundVolumePercent", 100)
        config.set<List<String>>("ignoredPlayers", emptyList<String>())
        config.set<List<String>>("favorites", emptyList<String>())

        EmotifyClientConfig.spec.correct(config)
        config.get<Boolean>("showCustomEmotions") shouldBe false
        config.get<List<String>>("favorites") shouldBe emptyList()
    }
})
