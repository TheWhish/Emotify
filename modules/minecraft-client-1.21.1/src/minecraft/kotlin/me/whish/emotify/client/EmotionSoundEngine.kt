package me.whish.emotify.client

import me.whish.emotify.client.sound.EmotionSoundPlayback
import me.whish.emotify.domain.EmotionId
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
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
}
