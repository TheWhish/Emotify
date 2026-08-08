package me.whish.emotify.fabric.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.whish.emotify.client.EmotifySettingsScreen

class EmotifyModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<EmotifySettingsScreen> =
        ConfigScreenFactory(::EmotifySettingsScreen)
}
