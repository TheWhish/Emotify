package me.whish.emotify.client.sound

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class EmotionSoundPlaybackTest : FunSpec({
    val emotionId = EmotionId.of("emotify:happy")

    test("empty sound catalog remains completely silent") {
        var calls = 0
        val playback = EmotionSoundPlayback<String, String>(emptyMap()) { _, _, _ -> calls++ }

        playback.play(emotionId, 100, "player") shouldBe false
        calls shouldBe 0
    }

    test("zero volume skips mapped sound output") {
        var calls = 0
        val playback = EmotionSoundPlayback<String, String>(mapOf(emotionId to "sound")) { _, _, _ -> calls++ }

        playback.play(emotionId, 0, "player") shouldBe false
        calls shouldBe 0
    }

    test("mapped sound receives normalized user volume once") {
        var playedSound = ""
        var playedVolume = -1.0f
        var playedContext = ""
        val playback = EmotionSoundPlayback<String, String>(mapOf(emotionId to "sound")) { sound, volume, context ->
            playedSound = sound
            playedVolume = volume
            playedContext = context
        }

        playback.play(emotionId, 35, "player") shouldBe true
        playedSound shouldBe "sound"
        playedVolume.shouldBeExactly(0.35f)
        playedContext shouldBe "player"
    }

    test("invalid volume fails before output") {
        val playback = EmotionSoundPlayback<String, String>(emptyMap()) { _, _, _ -> }

        shouldThrow<IllegalArgumentException> {
            playback.play(emotionId, -1, "player")
        }
    }
})
