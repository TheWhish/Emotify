package me.whish.emotify.client

import com.electronwill.nightconfig.core.CommentedConfig
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.settings.ClientConfigurationVersion
import me.whish.emotify.domain.CustomEmojiId

@Suppress("unused")
class EmotifyClientConfigSpecTest {
    @Test
    fun `empty favorites remain valid across NeoForge config correction`() {
        val config = CommentedConfig.inMemory()
        config.set<Int>("configVersion", ClientConfigurationSchema.CURRENT_VERSION)
        config.set<Boolean>("showOtherPlayersEmotions", true)
        config.set<Boolean>("showCustomEmotions", false)
        config.set<Boolean>("customCopyHintDismissed", true)
        config.set<Boolean>("reducedMotion", false)
        config.set<Int>("soundVolumePercent", 100)
        config.set<List<String>>("ignoredPlayers", emptyList<String>())
        config.set<List<String>>("favorites", emptyList<String>())
        config.set<List<String>>("quickSlots", List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { "" })

        EmotifyClientConfig.spec.correct(config)
        config.get<Int>("configVersion") shouldBe ClientConfigurationSchema.CURRENT_VERSION
        config.get<Boolean>("showCustomEmotions") shouldBe false
        config.get<Boolean>("customCopyHintDismissed") shouldBe true
        config.get<List<String>>("favorites") shouldBe emptyList()
        config.get<List<String>>("quickSlots") shouldBe List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { "" }
    }

    @Test
    fun `future config version remains valid to the spec when explicitly inspected`() {
        val config = CommentedConfig.inMemory()
        config.set<Int>("configVersion", ClientConfigurationSchema.CURRENT_VERSION + 1)
        config.set<Boolean>("showOtherPlayersEmotions", true)
        config.set<Boolean>("showCustomEmotions", true)
        config.set<Boolean>("customCopyHintDismissed", false)
        config.set<Boolean>("reducedMotion", false)
        config.set<Int>("soundVolumePercent", 100)
        config.set<List<String>>("ignoredPlayers", emptyList<String>())
        config.set<List<String>>("favorites", emptyList<String>())
        config.set<List<String>>("quickSlots", List(ClientConfigurationSchema.QUICK_SLOT_COUNT) { "" })

        EmotifyClientConfig.spec.correct(config)

        config.get<Int>("configVersion") shouldBe ClientConfigurationSchema.CURRENT_VERSION + 1
    }

    @Test
    fun `TOML preflight classifies legacy previous current and future schemas before registration`() {
        NeoForgeClientConfigVersionCodec.inspect("reducedMotion = true\n") shouldBe
            ClientConfigurationVersion.Legacy
        NeoForgeClientConfigVersionCodec.inspect("configVersion = 1 # previous\n") shouldBe
            ClientConfigurationVersion.SchemaOne
        NeoForgeClientConfigVersionCodec.inspect("configVersion = 2 # current\n") shouldBe
            ClientConfigurationVersion.Current
        NeoForgeClientConfigVersionCodec.inspect("configVersion=3\nfutureOption=true\n") shouldBe
            ClientConfigurationVersion.Future(3)

        shouldThrow<IllegalArgumentException> {
            NeoForgeClientConfigVersionCodec.inspect("configVersion=1\nconfigVersion=2\n")
        }
        shouldThrow<IllegalArgumentException> {
            NeoForgeClientConfigVersionCodec.inspect("configVersion=invalid\n")
        }
    }

    @Test
    fun `quick slots retain custom emotions independently from favorites`() {
        val custom = CustomEmojiId(1L, 2L, 3L).emotionId

        decodeNeoForgeQuickSlotIds(
            listOf(custom.value) + List(ClientConfigurationSchema.QUICK_SLOT_COUNT - 1) { "" },
        ).shouldContainExactly(
            custom,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
    }
}
