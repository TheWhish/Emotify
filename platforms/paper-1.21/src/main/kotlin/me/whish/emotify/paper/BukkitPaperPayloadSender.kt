package me.whish.emotify.paper

import me.whish.emotify.paper.network.PaperProtocolChannels
import me.whish.emotify.paper.network.PaperProtocolV1Bridge
import me.whish.emotify.paper.runtime.PaperConnectionIngress
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PreparedCustomEmojiAssetDelivery
import me.whish.emotify.server.core.PreparedCustomEmotionDelivery
import me.whish.emotify.server.core.PreparedEmotionDelivery
import me.whish.emotify.server.core.PreparedServerHelloDelivery
import me.whish.emotify.paper.runtime.PaperGlobalTasks
import me.whish.emotify.wire.v1.ProtocolV1Channels
import org.bukkit.plugin.Plugin

internal class BukkitPaperOutboundTransport(
    private val plugin: Plugin,
    private val connections: PaperConnectionIngress,
    private val customAssetChunkPayloads: PaperCustomAssetChunkPayloadCache = PaperCustomAssetChunkPayloadCache(),
) : OutboundTransport {
    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        val encoded = PaperProtocolV1Bridge.encodeServerHello(hello)
        return PreparedServerHelloDelivery { connection ->
            send(connection, ProtocolV1Channels.SERVER_HELLO, encoded)
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus = send(
        connection,
        ProtocolV1Channels.SELECTION_REJECTED,
        PaperProtocolV1Bridge.encodeSelectionRejected(rejection),
    )

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery {
        val encoded = PaperProtocolV1Bridge.encodePlay(play)
        return PreparedEmotionDelivery { playerId, connectionId ->
            send(ConnectionKey(playerId, connectionId), ProtocolV1Channels.PLAY, encoded)
        }
    }

    override fun prepareCustomEmojiAsset(transfer: CustomEmojiTransfer): PreparedCustomEmojiAssetDelivery {
        return prepareCustomEmojiAsset(transfer, null)
    }

    override fun prepareCustomEmojiAsset(
        transfer: CustomEmojiTransfer,
        losslessChunks: List<CustomEmojiAssetChunk>?,
    ): PreparedCustomEmojiAssetDelivery {
        if (transfer.asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
            val chunks = java.util.List.copyOf(
                requireNotNull(losslessChunks) { "A large custom asset requires prepared lossless chunks" },
            )
            val encodedChunks by lazy(LazyThreadSafetyMode.NONE) {
                customAssetChunkPayloads.payloads(transfer.asset.id, chunks)
            }
            return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
                val connection = ConnectionKey(playerId, connectionId)
                encodedChunks.fold(OutboundDeliveryStatus.SENT) { status, encoded ->
                    if (status == OutboundDeliveryStatus.SENT) {
                        send(connection, ProtocolV1Channels.CUSTOM_ASSET_CHUNK, encoded)
                    } else {
                        status
                    }
                }
            }
        }
        val encoded by lazy(LazyThreadSafetyMode.NONE) { PaperProtocolV1Bridge.encodeCustomAsset(transfer) }
        return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
            send(ConnectionKey(playerId, connectionId), ProtocolV1Channels.CUSTOM_ASSET, encoded)
        }
    }

    override fun prepareCustomEmotionPlay(play: CustomEmotionPlay): PreparedCustomEmotionDelivery {
        val encoded = PaperProtocolV1Bridge.encodeCustomPlay(play)
        return PreparedCustomEmotionDelivery { playerId, connectionId ->
            send(ConnectionKey(playerId, connectionId), ProtocolV1Channels.CUSTOM_PLAY, encoded)
        }
    }

    private fun send(
        connection: ConnectionKey,
        channel: String,
        body: ByteArray,
    ): OutboundDeliveryStatus {
        check(PaperGlobalTasks.isGlobalThread(plugin.server)) { "Paper payloads must be sent on the global server thread" }
        val player = plugin.server.getPlayer(connection.playerId)
            ?.takeIf { candidate -> candidate.isOnline }
            ?: return OutboundDeliveryStatus.UNAVAILABLE
        if (!connections.isActive(connection, player)) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        if (
            PaperProtocolChannels.requiresBukkitSubscription(channel) &&
            !connections.supportsOutgoingChannel(connection, channel)
        ) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        player.sendPluginMessage(plugin, channel, body)
        return OutboundDeliveryStatus.SENT
    }

    companion object {
        private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
    }
}
