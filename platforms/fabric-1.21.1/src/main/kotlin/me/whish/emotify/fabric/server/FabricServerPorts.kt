package me.whish.emotify.fabric.server

import java.util.UUID
import me.whish.emotify.fabric.network.FabricChannelSupport
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.server.core.AudiencePort
import me.whish.emotify.server.core.AudienceVisitCompletion
import me.whish.emotify.server.core.AudienceVisitor
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.OutboundTransport
import me.whish.emotify.server.core.PlayerSnapshot
import me.whish.emotify.server.core.PreparedEmotionDelivery
import me.whish.emotify.server.core.PreparedServerHelloDelivery
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal class FabricAudiencePort(
    private val server: MinecraftServer,
) : AudiencePort {
    override fun visitTracking(
        source: PlayerSnapshot,
        maxCandidates: Int,
        visitor: AudienceVisitor,
    ): AudienceVisitCompletion {
        require(maxCandidates > 0) { "Maximum audience candidate count must be positive: $maxCandidates" }
        val sourcePlayer = resolve(source.connection) ?: return AudienceVisitCompletion.EXHAUSTED
        var candidateCount = 0
        for (recipient in PlayerLookup.tracking(sourcePlayer)) {
            if (recipient.uuid == sourcePlayer.uuid) {
                continue
            }
            if (candidateCount >= maxCandidates) {
                return AudienceVisitCompletion.LIMIT_REACHED
            }
            candidateCount += 1
            val state = FabricServerConnectionRegistry.current(recipient.uuid, recipient.connection) ?: continue
            val sameDimension = sourcePlayer.serverLevel() === recipient.serverLevel()
            val shouldContinue = visitor.visit(
                recipient.uuid,
                state.connectionId,
                !sourcePlayer.isInvisibleTo(recipient),
                sameDimension,
                if (sameDimension) sourcePlayer.distanceToSqr(recipient) else Double.POSITIVE_INFINITY,
            )
            if (!shouldContinue) {
                return AudienceVisitCompletion.LIMIT_REACHED
            }
        }
        return AudienceVisitCompletion.EXHAUSTED
    }

    private fun resolve(connection: ConnectionKey): ServerPlayer? {
        val player = server.playerList.getPlayer(connection.playerId) ?: return null
        val state = FabricServerConnectionRegistry.current(connection.playerId, player.connection) ?: return null
        return player.takeIf { state.connectionId == connection.connectionId }
    }
}

internal class FabricOutboundTransport(
    private val server: MinecraftServer,
) : OutboundTransport {
    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        val payload = FabricServerHelloPayload(hello)
        return PreparedServerHelloDelivery { connection ->
            send(connection.playerId, connection.connectionId) { player ->
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus = send(
        connection.playerId,
        connection.connectionId,
    ) { player ->
        ServerPlayNetworking.send(player, FabricSelectionRejectedPayload(rejection))
    }

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery {
        val payload = FabricEmotionPlayPayload(play)
        return PreparedEmotionDelivery { playerId, connectionId ->
            send(playerId, connectionId) { player ->
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    private fun send(
        playerId: UUID,
        connectionId: ConnectionId,
        delivery: (ServerPlayer) -> Unit,
    ): OutboundDeliveryStatus {
        val player = server.playerList.getPlayer(playerId) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val state = FabricServerConnectionRegistry.current(playerId, player.connection)
            ?: return OutboundDeliveryStatus.UNAVAILABLE
        if (
            state.connectionId != connectionId ||
            !player.connection.isAcceptingMessages ||
            !FabricChannelSupport.clientCanReceiveServerPayloads(player)
        ) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        delivery(player)
        return OutboundDeliveryStatus.SENT
    }
}
