package me.whish.emotify.client

import me.whish.emotify.client.sound.EmotionSoundPlayback
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.domain.EmotionId
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player

internal object EmotionSoundEngine {
    private val playback = EmotionSoundPlayback<SoundEvent, Player>(emptyMap()) { sound, volume, source ->
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(
                sound,
                SoundSource.PLAYERS,
                volume,
                1.0f,
                source.random,
                source.blockPosition(),
            ),
        )
    }

    fun play(emotionId: EmotionId, source: Player, volumePercent: Int): Boolean =
        playback.play(emotionId, volumePercent, source)

    fun playCopyConfirmation(volumePercent: Int): Boolean {
        require(
            volumePercent in ClientSettingsSnapshot.MINIMUM_SOUND_VOLUME_PERCENT..
                ClientSettingsSnapshot.MAXIMUM_SOUND_VOLUME_PERCENT,
        ) { "Sound volume is outside the supported range: $volumePercent" }
        if (volumePercent == 0) {
            return false
        }
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(
                SoundEvents.EXPERIENCE_ORB_PICKUP,
                1.15f,
                volumePercent / 100.0f,
            ),
        )
        return true
    }

    fun playInterfaceClick() {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(
                SoundEvents.UI_BUTTON_CLICK.value(),
                1.0f,
                0.45f,
            ),
        )
    }
}

