package me.whish.emotify.network

import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import net.minecraft.network.Connection

fun interface ServerHelloReceiver {
    fun receive(connection: Connection, envelope: ServerHelloEnvelope)
}

fun interface SelectionRejectedReceiver {
    fun receive(connection: Connection, rejection: SelectionRejected)
}

fun interface EmotionPlayReceiver {
    fun receive(connection: Connection, play: EmotionPlay)
}

object ClientPayloadReceiver {
    private val uninstalled = Receivers(
        { _, _ -> },
        { _, _ -> },
        { _, _ -> },
    )

    @Volatile
    private var receivers = uninstalled

    fun install(
        serverHello: ServerHelloReceiver,
        selectionRejected: SelectionRejectedReceiver,
        emotionPlay: EmotionPlayReceiver,
    ) {
        check(receivers === uninstalled) { "Client payload receivers are already installed" }
        receivers = Receivers(serverHello, selectionRejected, emotionPlay)
    }

    fun receive(connection: Connection, envelope: ServerHelloEnvelope) {
        receivers.serverHello.receive(connection, envelope)
    }

    fun receive(connection: Connection, rejection: SelectionRejected) {
        receivers.selectionRejected.receive(connection, rejection)
    }

    fun receive(connection: Connection, play: EmotionPlay) {
        receivers.emotionPlay.receive(connection, play)
    }

    private data class Receivers(
        val serverHello: ServerHelloReceiver,
        val selectionRejected: SelectionRejectedReceiver,
        val emotionPlay: EmotionPlayReceiver,
    )
}
