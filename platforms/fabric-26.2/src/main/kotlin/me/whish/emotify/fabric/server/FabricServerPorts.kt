package me.whish.emotify.fabric.server

import java.util.UUID
import me.whish.emotify.fabric.network.FabricChannelSupport
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetChunkPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionPlayPayload
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionPlay
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
import me.whish.emotify.server.core.PreparedCustomEmojiAssetDelivery
import me.whish.emotify.server.core.PreparedCustomEmotionDelivery
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
            val sameDimension = sourcePlayer.level() === recipient.level()
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

    override fun prepareCustomEmojiAsset(transfer: CustomEmojiTransfer): PreparedCustomEmojiAssetDelivery {
        return prepareCustomEmojiAsset(transfer, null)
    }

    override fun prepareCustomEmojiAsset(
        transfer: CustomEmojiTransfer,
        losslessChunks: List<CustomEmojiAssetChunk>?,
    ): PreparedCustomEmojiAssetDelivery {
        if (transfer.asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE) {
            val chunks = requireNotNull(losslessChunks) {
                "A large custom asset requires prepared lossless chunks"
            }
            val payloads by lazy(LazyThreadSafetyMode.NONE) {
                chunks.map(::FabricCustomEmojiAssetChunkPayload)
            }
            return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
                sendCustom(playerId, connectionId, requireLossless = true) { player ->
                    payloads.forEach { payload -> ServerPlayNetworking.send(player, payload) }
                }
            }
        }
        val payload = FabricCustomEmojiAssetPayload.prepared(transfer)
        return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
            sendCustom(playerId, connectionId) { player -> ServerPlayNetworking.send(player, payload) }
        }
    }

    override fun prepareCustomEmotionPlay(play: CustomEmotionPlay): PreparedCustomEmotionDelivery {
        val payload = FabricCustomEmotionPlayPayload(play)
        return PreparedCustomEmotionDelivery { playerId, connectionId ->
            sendCustom(playerId, connectionId) { player -> ServerPlayNetworking.send(player, payload) }
        }
    }

    private fun sendCustom(
        playerId: UUID,
        connectionId: ConnectionId,
        requireLossless: Boolean = false,
        delivery: (ServerPlayer) -> Unit,
    ): OutboundDeliveryStatus {
        val player = server.playerList.getPlayer(playerId) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val state = FabricServerConnectionRegistry.current(playerId, player.connection)
            ?: return OutboundDeliveryStatus.UNAVAILABLE
        if (
            state.connectionId != connectionId ||
            !player.connection.isAcceptingMessages ||
            !FabricChannelSupport.clientCanReceiveCustomEmojis(player) ||
            requireLossless && !FabricChannelSupport.clientCanReceiveLosslessCustomEmojis(player)
        ) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        delivery(player)
        return OutboundDeliveryStatus.SENT
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

    companion object {
        private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
    }
}

