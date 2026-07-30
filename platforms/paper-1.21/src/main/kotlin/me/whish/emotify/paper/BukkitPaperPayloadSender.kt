package me.whish.emotify.paper

import me.whish.emotify.paper.network.PaperProtocolV1Bridge
import me.whish.emotify.paper.runtime.PaperConnectionIngress
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PreparedEmotionDelivery
import me.whish.emotify.server.core.PreparedServerHelloDelivery
import me.whish.emotify.wire.v1.ProtocolV1Channels
import org.bukkit.plugin.Plugin

internal class BukkitPaperOutboundTransport(
    private val plugin: Plugin,
    private val connections: PaperConnectionIngress,
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

    private fun send(
        connection: ConnectionKey,
        channel: String,
        body: ByteArray,
    ): OutboundDeliveryStatus {
        check(plugin.server.isPrimaryThread) { "Paper payloads must be sent on the primary server thread" }
        if (!connections.isActive(connection)) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        val player = plugin.server.getPlayer(connection.playerId)
            ?.takeIf { candidate -> candidate.isOnline }
            ?: return OutboundDeliveryStatus.UNAVAILABLE
        if (!connections.supportsOutgoingChannel(connection, channel)) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        player.sendPluginMessage(plugin, channel, body)
        return OutboundDeliveryStatus.SENT
    }
}
