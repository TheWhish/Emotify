package me.whish.emotify.client.sound

import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.domain.EmotionId

fun interface EmotionSoundOutput<S : Any, C : Any> {
    fun play(sound: S, volume: Float, context: C)
}

class EmotionSoundPlayback<S : Any, C : Any>(
    catalog: Map<EmotionId, S>,
    private val output: EmotionSoundOutput<S, C>,
) {
    private val sounds = java.util.Map.copyOf(catalog)

    fun play(emotionId: EmotionId, volumePercent: Int, context: C): Boolean {
        require(
            volumePercent in ClientSettingsSnapshot.MINIMUM_SOUND_VOLUME_PERCENT..
                ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
        ) { "Sound volume is outside the supported range: $volumePercent" }
        if (volumePercent == 0) {
            return false
        }
        val sound = sounds[emotionId] ?: return false
        output.play(sound, volumePercent / 100.0f, context)
        return true
    }
}
