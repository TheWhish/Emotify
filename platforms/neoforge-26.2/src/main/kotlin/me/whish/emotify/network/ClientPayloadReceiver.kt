package me.whish.emotify.network

import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
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

fun interface CustomEmojiAssetReceiver {
    fun receive(connection: Connection, transfer: CustomEmojiTransfer)
}

fun interface CustomEmojiAssetChunkReceiver {
    fun receive(connection: Connection, chunk: CustomEmojiAssetChunk)
}

fun interface CustomEmotionPlayReceiver {
    fun receive(connection: Connection, play: CustomEmotionPlay)
}

object ClientPayloadReceiver {
    private val uninstalled = Receivers(
        { _, _ -> },
        { _, _ -> },
        { _, _ -> },
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
        customEmojiAsset: CustomEmojiAssetReceiver,
        customEmojiAssetChunk: CustomEmojiAssetChunkReceiver,
        customEmotionPlay: CustomEmotionPlayReceiver,
    ) {
        check(receivers === uninstalled) { "Client payload receivers are already installed" }
        receivers = Receivers(
            serverHello,
            selectionRejected,
            emotionPlay,
            customEmojiAsset,
            customEmojiAssetChunk,
            customEmotionPlay,
        )
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

    fun receive(connection: Connection, transfer: CustomEmojiTransfer) {
        receivers.customEmojiAsset.receive(connection, transfer)
    }

    fun receive(connection: Connection, chunk: CustomEmojiAssetChunk) {
        receivers.customEmojiAssetChunk.receive(connection, chunk)
    }

    fun receive(connection: Connection, play: CustomEmotionPlay) {
        receivers.customEmotionPlay.receive(connection, play)
    }

    private data class Receivers(
        val serverHello: ServerHelloReceiver,
        val selectionRejected: SelectionRejectedReceiver,
        val emotionPlay: EmotionPlayReceiver,
        val customEmojiAsset: CustomEmojiAssetReceiver,
        val customEmojiAssetChunk: CustomEmojiAssetChunkReceiver,
        val customEmotionPlay: CustomEmotionPlayReceiver,
    )
}
