package me.whish.emotify.client

import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.common.NeoForge

object CustomEmojiCopyInput {
    private var consumeOffhandUse = false

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onInteraction)
    }

    private fun onInteraction(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) {
            return
        }
        val intercepted = if (event.hand == InteractionHand.MAIN_HAND) {
            CustomEmojiCopyController.intercept(Minecraft.getInstance()).also { consume ->
                consumeOffhandUse = consume
            }
        } else {
            consumeOffhandUse.also { consumeOffhandUse = false }
        }
        if (!intercepted) {
            return
        }
        event.isCanceled = true
        event.setSwingHand(false)
    }
}
