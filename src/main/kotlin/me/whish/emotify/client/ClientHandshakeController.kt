package me.whish.emotify.client

import me.whish.emotify.Emotify
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.protocol.EmotifyProtocol
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHelloEnvelope
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent

object ClientHandshakeController {
    private val session = ClientHandshakeSession(
        EmotifyProtocol.capabilities,
        EmotionCatalog.BUILT_IN,
        SystemMonotonicTimeSource,
    )

    private var activeConnection: Connection? = null
    private var activeConnectionId = 0L
    private val helloResponseGate = ClientHelloResponseGate()
    private val selectionResponseGate = ClientSelectionResponseGate()
    private val playGate = ClientPlayGate()
    private val activeEmotions = ClientActiveEmotionStore(SystemMonotonicTimeSource)
    private var playDropDiagnostics = newPlayDropDiagnostics()

    val state: ClientHandshakeState
        get() = session.state

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onLoggingIn)
        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
        NeoForge.EVENT_BUS.addListener(::onPlayerClone)
        NeoForge.EVENT_BUS.addListener(::onLevelUnload)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    fun renderableEmotionFor(player: AbstractClientPlayer): ActiveEmotion? {
        if (!isEligibleForEmotion(player)) return null
        val active = activeEmotions.visibleFor(player.id, player.uuid) ?: return null
        return knownEmotionOrDiscard(player, active)
    }

    fun shouldHideNameTagFor(player: AbstractClientPlayer): Boolean {
        if (!isEligibleForEmotion(player)) return false
        val active = activeEmotions.find(player.id, player.uuid) ?: return false
        if (knownEmotionOrDiscard(player, active) == null) return false
        return activeEmotions.shouldHideNameTagFor(player.id, player.uuid)
    }

    fun discardEmotionFor(entityId: Int, sourceUuid: java.util.UUID) {
        activeEmotions.discard(entityId, sourceUuid)
    }

    private fun isEligibleForEmotion(player: AbstractClientPlayer): Boolean {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player
        val eligible =
            localPlayer != null &&
                player.level() === minecraft.level &&
                player.isAlive &&
                !player.isRemoved &&
                !player.isSpectator &&
                !player.isInvisible &&
                !player.isInvisibleTo(localPlayer)
        if (!eligible) {
            discardEmotionFor(player.id, player.uuid)
        }
        return eligible
    }

    private fun knownEmotionOrDiscard(player: AbstractClientPlayer, active: ActiveEmotion): ActiveEmotion? {
        if (EmotionPresentationCatalog.find(active.emotionId) != null) {
            return active
        }
        discardEmotionFor(player.id, player.uuid)
        return null
    }

    fun pickerContext(): EmotionPickerContext? {
        val supported = state as? ClientHandshakeState.Supported ?: return null
        if (activeConnection == null || supported.connectionId != activeConnectionId) {
            return null
        }
        return EmotionPickerContext(activeConnectionId, supported.policy.allowedEmotions)
    }

    fun receive(connection: Connection, envelope: ServerHelloEnvelope) {
        if (activeConnection !== connection) {
            return
        }
        val transition = when (envelope) {
            is ServerHelloEnvelope.Valid -> {
                if (!sendClientHelloResponse(connection)) {
                    return
                }
                session.receiveServerHello(activeConnectionId, envelope.hello)
            }
            ServerHelloEnvelope.DuplicateEmotionIds -> session.rejectDuplicateServerCatalog(activeConnectionId)
        }
        logTransition(transition)
    }

    fun receive(connection: Connection, rejection: SelectionRejected) {
        if (activeConnection !== connection || state !is ClientHandshakeState.Supported) {
            return
        }
        if (!selectionResponseGate.tryConsumeRejection()) {
            return
        }
        val reason = rejection.code.knownReason?.name ?: "UNKNOWN"
        Emotify.LOGGER.info(
            "Emotify selection rejected on connection {}: reason={}, reasonCode={}, retryAfterMillis={}",
            activeConnectionId,
            reason,
            rejection.code.value,
            rejection.retryAfterMillis,
        )
        Minecraft.getInstance().player?.displayClientMessage(rejection.userMessage(), true)
    }

    fun receive(connection: Connection, play: EmotionPlay) {
        if (activeConnection !== connection) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (supported.connectionId != activeConnectionId) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        val source = minecraft.level?.getEntity(play.entityId.value) as? net.minecraft.world.entity.player.Player ?: return
        if (!playGate.admit(
                activeConnectionId,
                supported.policy.allowedEmotions,
                play,
                source.id,
                source.uuid,
                !source.isInvisibleTo(localPlayer),
            )
        ) {
            return
        }
        if (source === localPlayer) {
            selectionResponseGate.tryConsumeSuccess()
        }
        val activation = activeEmotions.activate(activeConnectionId, play)
        if (activation != EmotionActivationResult.ADDED && activation != EmotionActivationResult.REPLACED) {
            if (playDropDiagnostics.tryConsume()) {
                Emotify.LOGGER.warn(
                    "Emotify play dropped on connection {}: result={}, emotion={}, entityId={}, sequence={}",
                    activeConnectionId,
                    activation,
                    play.emotionId,
                    play.entityId.value,
                    play.sequence.value,
                )
            }
        }
    }

    fun sendSelection(listener: ClientPacketListener, emotionId: EmotionId): ClientSelectionSendResult {
        if (activeConnection !== listener.connection) {
            return ClientSelectionSendResult.NOT_CONNECTED
        }
        val supported = state as? ClientHandshakeState.Supported
            ?: return ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE
        if (supported.connectionId != activeConnectionId) {
            return ClientSelectionSendResult.NOT_CONNECTED
        }
        if (!supported.policy.allowedEmotions.contains(emotionId)) {
            return ClientSelectionSendResult.EMOTION_UNAVAILABLE
        }
        val localPlayer = Minecraft.getInstance().player
        if (localPlayer != null && activeEmotions.visibleFor(localPlayer.id, localPlayer.uuid) != null) {
            return ClientSelectionSendResult.EMOTION_ACTIVE
        }
        if (!listener.hasChannel(EmotionSelectionPayload.TYPE)) {
            return ClientSelectionSendResult.CHANNEL_UNAVAILABLE
        }
        if (!selectionResponseGate.tryReserve()) {
            return ClientSelectionSendResult.REQUEST_PENDING
        }

        try {
            listener.send(EmotionSelectionPayload(EmotionSelection(emotionId)))
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            throw error
        }
        return ClientSelectionSendResult.SENT
    }

    private fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        activeConnectionId = Math.incrementExact(activeConnectionId)
        activeConnection = event.connection
        helloResponseGate.begin(activeConnectionId)
        selectionResponseGate.reset()
        playGate.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        playDropDiagnostics = newPlayDropDiagnostics()
        session.begin(activeConnectionId)

        val listener = event.player.connection
        val supportsHandshake = EmotifyChannels.supportsProtocol { type -> listener.hasChannel(type) }
        if (supportsHandshake) {
            Emotify.LOGGER.info("Emotify client handshake pending on connection {}", activeConnectionId)
        } else {
            Emotify.LOGGER.info("Emotify client handshake unavailable: optional channels are absent")
        }
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        val eventConnection = event.connection
        if (eventConnection != null && activeConnection !== eventConnection) {
            return
        }
        session.disconnect(activeConnectionId)
        activeConnection = null
        helloResponseGate.disconnect(activeConnectionId)
        selectionResponseGate.reset()
        playGate.disconnect(activeConnectionId)
        activeEmotions.disconnect(activeConnectionId)
    }

    private fun onPlayerClone(event: ClientPlayerNetworkEvent.Clone) {
        if (activeConnection === event.connection) {
            clearWorldState()
        }
    }

    private fun onLevelUnload(event: LevelEvent.Unload) {
        val level = event.level as? ClientLevel ?: return
        if (Minecraft.getInstance().level === level) {
            clearWorldState()
        }
    }

    private fun clearWorldState() {
        activeEmotions.clearWorld(activeConnectionId)
        selectionResponseGate.reset()
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        if (activeEmotions.size > 0) {
            activeEmotions.removeExpired()
            discardIneligibleEmotions()
        }
        logTransition(session.pollTimeout())
    }

    private fun discardIneligibleEmotions() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val localPlayer = minecraft.player
        if (level == null || localPlayer == null) {
            activeEmotions.clearWorld(activeConnectionId)
            return
        }
        activeEmotions.discardIf { active ->
            val source = level.getEntity(active.entityId.value) as? Player
            source == null ||
                source.uuid != active.sourceUuid ||
                !source.isAlive ||
                source.isRemoved ||
                source.isSpectator ||
                source.isInvisible ||
                source.isInvisibleTo(localPlayer)
        }
    }

    private fun logTransition(transition: ClientHandshakeTransition) {
        when (transition) {
            ClientHandshakeTransition.SUPPORTED -> Emotify.LOGGER.info(
                "Emotify client handshake supported on connection {}",
                activeConnectionId,
            )
            ClientHandshakeTransition.POLICY_UPDATED -> Emotify.LOGGER.info(
                "Emotify client policy updated on connection {}",
                activeConnectionId,
            )
            ClientHandshakeTransition.UNSUPPORTED -> {
                activeEmotions.clearWorld(activeConnectionId)
                Emotify.LOGGER.warn(
                    "Emotify client handshake unsupported on connection {}: {}",
                    activeConnectionId,
                    session.state,
                )
            }
            ClientHandshakeTransition.NO_CHANGE,
            ClientHandshakeTransition.IGNORED,
            -> Unit
        }
    }

    private fun sendClientHelloResponse(connection: Connection): Boolean {
        if (helloResponseGate.hasResponded(activeConnectionId)) {
            return true
        }
        val listener = Minecraft.getInstance().connection ?: return false
        if (
            listener.connection !== connection ||
            !EmotifyChannels.supportsProtocol { type -> listener.hasChannel(type) }
        ) {
            return false
        }
        if (!helloResponseGate.tryRespond(activeConnectionId)) {
            return false
        }

        try {
            listener.send(ClientHelloPayload(EmotifyProtocol.clientHello))
        } catch (error: RuntimeException) {
            helloResponseGate.cancelResponse(activeConnectionId)
            throw error
        }
        Emotify.LOGGER.info("Emotify client hello sent on connection {}", activeConnectionId)
        return true
    }

    private fun newPlayDropDiagnostics(): TokenBucket = TokenBucket(
        capacity = PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND,
        timeSource = SystemMonotonicTimeSource,
    )

    private const val PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY = 4
    private const val PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND = 2

    private fun SelectionRejected.userMessage(): Component = when (code.knownReason) {
        SelectionRejectionReason.COOLDOWN -> Component.translatable("message.emotify.selection_cooldown")
        SelectionRejectionReason.SERVER_DISABLED,
        SelectionRejectionReason.EMOTION_DISABLED,
        -> Component.translatable("message.emotify.selection_unavailable")
        SelectionRejectionReason.PLAYER_STATE -> Component.translatable("message.emotify.player_state")
        SelectionRejectionReason.SERVER_BUSY -> Component.translatable("message.emotify.server_busy")
        null -> Component.translatable("message.emotify.selection_failed")
    }
}

enum class ClientSelectionSendResult {
    SENT,
    NOT_CONNECTED,
    HANDSHAKE_UNAVAILABLE,
    EMOTION_UNAVAILABLE,
    CHANNEL_UNAVAILABLE,
    REQUEST_PENDING,
    EMOTION_ACTIVE,
}
