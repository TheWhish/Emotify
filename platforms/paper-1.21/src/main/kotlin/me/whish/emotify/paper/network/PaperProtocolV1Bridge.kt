package me.whish.emotify.paper.network

import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs

object PaperProtocolChannels {
    val advertisedIncoming: List<String> = java.util.List.of(
        ProtocolV1Channels.SERVER_HELLO,
        ProtocolV1Channels.CLIENT_HELLO,
        ProtocolV1Channels.SELECT,
        ProtocolV1Channels.PLAY,
        ProtocolV1Channels.SELECTION_REJECTED,
    )

    val acceptedIncoming: List<String> = java.util.List.of(
        ProtocolV1Channels.CLIENT_HELLO,
        ProtocolV1Channels.SELECT,
    )

    val outgoing: List<String> = java.util.List.of(
        ProtocolV1Channels.SERVER_HELLO,
        ProtocolV1Channels.PLAY,
        ProtocolV1Channels.SELECTION_REJECTED,
    )

    fun acceptsIncoming(channel: String): Boolean =
        channel == ProtocolV1Channels.CLIENT_HELLO || channel == ProtocolV1Channels.SELECT
}

object PaperProtocolV1Bridge {
    fun decodeClientHello(body: ByteArray): ClientHello = ProtocolV1Codecs.clientHello.decode(body)

    fun decodeSelection(body: ByteArray): EmotionSelection = ProtocolV1Codecs.selection.decode(body)

    fun encodeServerHello(hello: ServerHello): ByteArray =
        ProtocolV1Codecs.serverHello.encodeToByteArray(ServerHelloEnvelope.Valid(hello))

    fun encodePlay(play: EmotionPlay): ByteArray = ProtocolV1Codecs.play.encodeToByteArray(play)

    fun encodeSelectionRejected(rejection: SelectionRejected): ByteArray =
        ProtocolV1Codecs.selectionRejected.encodeToByteArray(rejection)
}
