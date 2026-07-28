package me.whish.emotify.server

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.Emotify
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.network.ConnectionAttributes
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotifyProtocol
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

object ServerHandshakeService {
    private val connectionIds = AtomicLong()
    private val selectionPolicy = ServerSelectionPolicy(
        enabled = true,
        catalog = EmotifyProtocol.serverHello.emotionCatalog,
        allowedEmotions = EmotifyProtocol.serverHello.emotionCatalog,
    )
    private val sessions = ServerSessionRegistry(
        EmotifyProtocol.capabilities,
        EmotifyProtocol.SELECTION_COOLDOWN_MILLIS.milliseconds,
        SystemMonotonicTimeSource,
    )
    private val audienceBudget = AudienceBudget()
    private val dimensionOrdinals = DimensionOrdinalRegistry()
    private val eventSequence = ServerEventSequence()

    fun nextConnectionId(): Long = connectionIds.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "Server connection ID space is exhausted" }
        current + 1L
    }

    fun open(playerId: UUID, connectionId: Long): ServerPlayerSession =
        sessions.open(playerId, connectionId)

    fun receive(server: MinecraftServer, playerId: UUID, connectionId: Long, hello: ClientHello) {
        check(server.isSameThread) { "Emotify handshakes must be processed on the main server thread" }
        val session = sessions.get(playerId, connectionId) ?: return
        when (session.receiveClientHello(hello)) {
            ServerHandshakeTransition.SUPPORTED -> Emotify.LOGGER.info(
                "Emotify handshake supported for player {} on connection {}",
                playerId,
                connectionId,
            )
            ServerHandshakeTransition.UNSUPPORTED -> Emotify.LOGGER.warn(
                "Emotify handshake unsupported for player {} on connection {}: {}",
                playerId,
                connectionId,
                session.handshakeState,
            )
            ServerHandshakeTransition.NO_CHANGE -> Unit
        }
    }

    fun select(
        server: MinecraftServer,
        playerId: UUID,
        connectionId: Long,
        emotionId: EmotionId,
    ) {
        check(server.isSameThread) { "Emotify selections must be processed on the main server thread" }
        val session = sessions.get(playerId, connectionId) ?: return
        val player = server.playerList.getPlayer(playerId) ?: return
        val activeConnectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get()
        if (activeConnectionId != connectionId) {
            return
        }
        if (!EmotifyChannels.supportsProtocol { type -> player.connection.hasChannel(type) }) {
            return
        }

        val playerState = PlayerSelectionState(
            alive = player.isAlive,
            spectator = player.isSpectator,
            invisible = player.isInvisible,
        )
        when (val preparation = session.prepareSelection(emotionId, selectionPolicy, playerState)) {
            SelectionPreparation.Ready -> publish(player, session, emotionId)
            SelectionPreparation.Ignored -> Unit
            is SelectionPreparation.Rejected -> sendRejection(player, session, preparation)
        }
    }

    fun close(playerId: UUID, connectionId: Long): Boolean =
        sessions.close(playerId, connectionId)

    fun clear() {
        sessions.clear()
        audienceBudget.clear()
        dimensionOrdinals.clear()
        eventSequence.reset()
    }

    private fun publish(player: ServerPlayer, session: ServerPlayerSession, emotionId: EmotionId) {
        if (!eventSequence.hasCapacity()) {
            sendRejection(player, session, SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0))
            return
        }

        val dimensionId = dimensionOrdinals.resolve(player.level().dimension())
        val regionKey = ChunkPos.asLong(player.blockX shr 4, player.blockZ shr 4)
        when (audienceBudget.tryReserve(dimensionId, regionKey)) {
            AudienceReservation.GLOBAL_BUSY,
            AudienceReservation.REGION_BUSY,
            -> {
                sendRejection(player, session, SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_BUSY, 0))
                return
            }
            AudienceReservation.RESERVED -> Unit
        }

        val sequence = checkNotNull(eventSequence.nextOrNull()) {
            "Emotify event sequence exhausted after a successful capacity check"
        }
        val payload = EmotionPlayPayload(
            EmotionPlay(
                RuntimeEntityId.of(player.id),
                player.uuid,
                sequence,
                emotionId,
            ),
        )
        val delivered = NeoForgePlayAudience.send(player, payload, ::supportedSession)
        if (delivered == 0) {
            audienceBudget.refund(dimensionId, regionKey)
            return
        }
        session.commitSelection()
    }

    private fun supportedSession(player: ServerPlayer): ServerPlayerSession? {
        val connectionId = player.connection.connection
            .channel()
            .attr(ConnectionAttributes.serverConnectionId)
            .get() ?: return null
        val session = sessions.get(player.uuid, connectionId) ?: return null
        return session.takeIf { it.handshakeState is ServerHandshakeState.Supported }
    }

    private fun sendRejection(
        player: ServerPlayer,
        session: ServerPlayerSession,
        rejection: SelectionPreparation.Rejected,
    ) {
        if (!session.tryAdmitRejection() || !player.connection.hasChannel(SelectionRejectedPayload.TYPE)) {
            return
        }
        player.connection.send(
            SelectionRejectedPayload(
                SelectionRejected(
                    SelectionRejectionCode.from(rejection.reason),
                    rejection.retryAfterMillis,
                ),
            ),
        )
    }
}
