package me.whish.emotify.client

import net.minecraft.client.gui.components.EditBox

internal object EmotionPickerPlatform {
    @Suppress("unused")
    fun configureSearchBox(searchBox: EditBox) {
        searchBox.setTextShadow(false)
    }
}
