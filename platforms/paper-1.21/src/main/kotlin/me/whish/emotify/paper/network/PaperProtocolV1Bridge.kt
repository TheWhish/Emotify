package me.whish.emotify.paper.network

import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1PortableProfile

object PaperProtocolChannels {
    val advertisedIncoming: List<String> = java.util.List.of(
        ProtocolV1Channels.SERVER_HELLO,
        ProtocolV1Channels.CLIENT_HELLO,
        ProtocolV1Channels.SELECT,
        ProtocolV1Channels.PLAY,
        ProtocolV1Channels.SELECTION_REJECTED,
        ProtocolV1Channels.CUSTOM_SELECT,
        ProtocolV1Channels.CUSTOM_ASSET,
        ProtocolV1Channels.CUSTOM_ASSET_CHUNK,
        ProtocolV1Channels.CUSTOM_PLAY,
    )

    val acceptedIncoming: List<String> = java.util.List.of(
        ProtocolV1Channels.CLIENT_HELLO,
        ProtocolV1Channels.SELECT,
        ProtocolV1Channels.CUSTOM_SELECT,
        ProtocolV1Channels.CUSTOM_ASSET_CHUNK,
    )

    val outgoing: List<String> = java.util.List.of(
        ProtocolV1Channels.SERVER_HELLO,
        ProtocolV1Channels.PLAY,
        ProtocolV1Channels.SELECTION_REJECTED,
        ProtocolV1Channels.CUSTOM_ASSET,
        ProtocolV1Channels.CUSTOM_ASSET_CHUNK,
        ProtocolV1Channels.CUSTOM_PLAY,
    )

    fun acceptsIncoming(channel: String): Boolean =
        channel == ProtocolV1Channels.CLIENT_HELLO ||
            channel == ProtocolV1Channels.SELECT ||
            channel == ProtocolV1Channels.CUSTOM_SELECT ||
            channel == ProtocolV1Channels.CUSTOM_ASSET_CHUNK

    fun requiresBukkitSubscription(channel: String): Boolean =
        channel != ProtocolV1Channels.CUSTOM_ASSET &&
            channel != ProtocolV1Channels.CUSTOM_ASSET_CHUNK &&
            channel != ProtocolV1Channels.CUSTOM_PLAY
}

object PaperProtocolV1Bridge {
    fun decodeClientHello(body: ByteArray): ClientHello = ProtocolV1Codecs.clientHello.decode(body)

    fun decodeSelection(body: ByteArray): EmotionSelection = ProtocolV1Codecs.selection.decode(body)

    fun decodeCustomSelection(body: ByteArray): CustomEmotionSelection = ProtocolV1Codecs.customSelection.decode(body)

    fun decodeCustomAssetChunk(body: ByteArray): CustomEmojiAssetChunk = ProtocolV1Codecs.customAssetChunk.decode(body)

    fun encodeServerHello(hello: ServerHello): ByteArray =
        ProtocolV1Codecs.serverHello.encodeToByteArray(
            ServerHelloEnvelope.Valid(ProtocolV1PortableProfile.requireServerHello(hello)),
        )

    fun encodePlay(play: EmotionPlay): ByteArray = ProtocolV1Codecs.play.encodeToByteArray(play)

    fun encodeSelectionRejected(rejection: SelectionRejected): ByteArray =
        ProtocolV1Codecs.selectionRejected.encodeToByteArray(rejection)

    fun encodeCustomAsset(transfer: CustomEmojiTransfer): ByteArray =
        ProtocolV1Codecs.customAsset.encodeToByteArray(transfer)

    fun encodeCustomAssetChunk(chunk: CustomEmojiAssetChunk): ByteArray =
        ProtocolV1Codecs.customAssetChunk.encodeToByteArray(chunk)

    fun encodeCustomPlay(play: CustomEmotionPlay): ByteArray =
        ProtocolV1Codecs.customPlay.encodeToByteArray(play)
}
