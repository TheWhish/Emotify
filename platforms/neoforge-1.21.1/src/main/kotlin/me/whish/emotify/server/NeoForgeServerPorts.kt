package me.whish.emotify.server

import java.util.UUID
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.network.payload.CustomEmojiAssetPayload
import me.whish.emotify.network.payload.CustomEmojiAssetChunkPayload
import me.whish.emotify.network.payload.CustomEmotionPlayPayload
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
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal class NeoForgeAudiencePort(
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
        for (recipient in sourcePlayer.serverLevel().chunkSource.chunkMap.getPlayersWatching(sourcePlayer)) {
            if (candidateCount >= maxCandidates) {
                return AudienceVisitCompletion.LIMIT_REACHED
            }
            candidateCount += 1
            val connectionId = recipient.connection.connection
                .channel()
                .attr(ConnectionAttributes.serverConnectionId)
                .get()
                ?.let(ConnectionId::parse)
                ?: continue
            val sameDimension = sourcePlayer.serverLevel() === recipient.serverLevel()
            val shouldContinue = visitor.visit(
                recipient.uuid,
                connectionId,
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
        val activeConnectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get()
        return player.takeIf { activeConnectionId == connection.connectionId.value }
    }
}

internal class NeoForgeOutboundTransport(
    private val server: MinecraftServer,
) : OutboundTransport {
    override fun prepareServerHello(hello: ServerHello): PreparedServerHelloDelivery {
        val payload = ServerHelloPayload(hello)
        return PreparedServerHelloDelivery { connection ->
            send(connection.playerId, connection.connectionId, ServerHelloPayload.TYPE) { player ->
                player.connection.send(payload)
            }
        }
    }

    override fun sendSelectionRejected(
        connection: ConnectionKey,
        rejection: SelectionRejected,
    ): OutboundDeliveryStatus = send(
        connection.playerId,
        connection.connectionId,
        SelectionRejectedPayload.TYPE,
    ) { player ->
        player.connection.send(SelectionRejectedPayload(rejection))
    }

    override fun prepareEmotionPlay(play: EmotionPlay): PreparedEmotionDelivery {
        val payload = EmotionPlayPayload(play)
        return PreparedEmotionDelivery { playerId, connectionId ->
            send(playerId, connectionId, EmotionPlayPayload.TYPE) { player ->
                player.connection.send(payload)
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
                chunks.map(::CustomEmojiAssetChunkPayload)
            }
            return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
                payloads.fold(OutboundDeliveryStatus.SENT) { status, payload ->
                    if (status != OutboundDeliveryStatus.SENT) {
                        status
                    } else {
                        sendCustom(playerId, connectionId, requireLossless = true) { player ->
                            player.connection.send(payload)
                        }
                    }
                }
            }
        }
        val payload = CustomEmojiAssetPayload.prepared(transfer)
        return PreparedCustomEmojiAssetDelivery { playerId, connectionId ->
            sendCustom(playerId, connectionId) { player ->
                player.connection.send(payload)
            }
        }
    }

    override fun prepareCustomEmotionPlay(play: CustomEmotionPlay): PreparedCustomEmotionDelivery {
        val payload = CustomEmotionPlayPayload(play)
        return PreparedCustomEmotionDelivery { playerId, connectionId ->
            sendCustom(playerId, connectionId) { player ->
                player.connection.send(payload)
            }
        }
    }

    private fun sendCustom(
        playerId: UUID,
        connectionId: ConnectionId,
        requireLossless: Boolean = false,
        delivery: (ServerPlayer) -> Unit,
    ): OutboundDeliveryStatus {
        val player = server.playerList.getPlayer(playerId) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val connection = player.connection.connection
        val activeConnectionId = connection.channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get()
        val supportsCustom = if (requireLossless) {
            EmotifyChannels.clientCanReceiveLosslessCustomEmojis(player.connection::hasChannel)
        } else {
            EmotifyChannels.clientCanReceiveCustomEmojis(player.connection::hasChannel)
        }
        if (activeConnectionId != connectionId.value || !connection.isConnected || !supportsCustom) {
            return OutboundDeliveryStatus.UNAVAILABLE
        }
        delivery(player)
        return OutboundDeliveryStatus.SENT
    }

    private fun <TPayload : net.minecraft.network.protocol.common.custom.CustomPacketPayload> send(
        playerId: UUID,
        connectionId: ConnectionId,
        payloadType: net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<TPayload>,
        delivery: (ServerPlayer) -> Unit,
    ): OutboundDeliveryStatus {
        val player = server.playerList.getPlayer(playerId) ?: return OutboundDeliveryStatus.UNAVAILABLE
        val connection = player.connection.connection
        val activeConnectionId = connection.channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get()
        if (
            activeConnectionId != connectionId.value ||
            !connection.isConnected ||
            !player.connection.hasChannel(payloadType)
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
