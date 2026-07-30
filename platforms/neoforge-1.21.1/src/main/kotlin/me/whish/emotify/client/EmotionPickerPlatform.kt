package me.whish.emotify.client

import net.minecraft.client.gui.components.EditBox

internal object EmotionPickerPlatform {
    fun configureSearchBox(searchBox: EditBox) {
        searchBox.setTextShadow(false)
    }
}
