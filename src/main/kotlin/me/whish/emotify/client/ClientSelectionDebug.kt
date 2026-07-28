package me.whish.emotify.client

import com.mojang.brigadier.Command
import me.whish.emotify.domain.EmotionId
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.common.NeoForge

object ClientSelectionDebug {
    private val emotionId = EmotionId.of("emotify:happy")

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegisterCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("emotifydebug").executes { context ->
                val listener = Minecraft.getInstance().connection
                val result = if (listener == null) {
                    ClientSelectionSendResult.NOT_CONNECTED
                } else {
                    ClientHandshakeController.sendSelection(listener, emotionId)
                }
                context.source.sendSuccess(
                    { Component.literal(result.message) },
                    false,
                )
                if (result == ClientSelectionSendResult.SENT) Command.SINGLE_SUCCESS else 0
            },
        )
    }

    private val ClientSelectionSendResult.message: String
        get() = when (this) {
            ClientSelectionSendResult.SENT -> "Emotify debug: sent emotify:happy"
            ClientSelectionSendResult.NOT_CONNECTED -> "Emotify debug: no active connection"
            ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE -> "Emotify debug: handshake is not ready or unsupported"
            ClientSelectionSendResult.EMOTION_UNAVAILABLE -> "Emotify debug: emotify:happy is disabled by the server"
            ClientSelectionSendResult.CHANNEL_UNAVAILABLE -> "Emotify debug: selection channel is unavailable"
            ClientSelectionSendResult.REQUEST_PENDING -> "Emotify debug: another selection is awaiting a response"
            ClientSelectionSendResult.EMOTION_ACTIVE -> "Emotify debug: the previous emotion is still active"
        }
}
