package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent

internal object PointerButtons {
    const val PRIMARY = 0
    const val SECONDARY = 1
    const val MIDDLE = 2
    const val UNKNOWN = -1

    fun portableIndex(event: MouseButtonEvent): Int = when (event.button()) {
        InputConstants.MOUSE_BUTTON_LEFT -> PRIMARY
        InputConstants.MOUSE_BUTTON_RIGHT -> SECONDARY
        InputConstants.MOUSE_BUTTON_MIDDLE -> MIDDLE
        else -> UNKNOWN
    }
}
