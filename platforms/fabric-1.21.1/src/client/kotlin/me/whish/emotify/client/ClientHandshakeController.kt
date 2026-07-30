package me.whish.emotify.client

import java.lang.ref.WeakReference
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.client.picker.ClientSelectionEligibility
import me.whish.emotify.client.picker.EmotionPickerContext
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.client.state.ActiveEmotion
import me.whish.emotify.client.state.ClientActiveEmotionStore
import me.whish.emotify.client.state.ClientHandshakeSession
import me.whish.emotify.client.state.ClientHandshakeState
import me.whish.emotify.client.state.ClientHandshakeTransition
import me.whish.emotify.client.state.ClientHelloResponseGate
import me.whish.emotify.client.state.ClientPlayGate
import me.whish.emotify.client.state.ClientPlayIngressGuard
import me.whish.emotify.client.state.ClientSelectionAttemptGate
import me.whish.emotify.client.state.ClientSelectionResponseGate
import me.whish.emotify.client.state.ClientSelectionSendResult
import me.whish.emotify.client.state.ClientServerHelloIngressGuard
import me.whish.emotify.client.state.EmotionActivationResult
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.network.payload.FabricClientHelloPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionSelectionPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.runtime.FabricProtocol
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHelloEnvelope
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player

object ClientHandshakeController {
    private val session = ClientHandshakeSession(
        FabricProtocol.capabilities,
        BuiltInEmotionCatalog.catalog,
        SystemMonotonicTimeSource,
    )
    private val helloResponseGate = ClientHelloResponseGate()
    private val serverHelloIngressGuard = ClientServerHelloIngressGuard(SystemMonotonicTimeSource)
    private val selectionResponseGate = ClientSelectionResponseGate()
    private val selectionAttemptGate = ClientSelectionAttemptGate()
    private val playIngressGuard = ClientPlayIngressGuard(SystemMonotonicTimeSource)
    private val playGate = ClientPlayGate()
    private val activeEmotions = ClientActiveEmotionStore(SystemMonotonicTimeSource)

    private var activeListener: ClientPacketListener? = null
    private var activeConnectionId = 0L
    private var localPlayerReference = WeakReference<LocalPlayer>(null)
    private var deferredServerHello: ServerHelloEnvelope? = null
    private var protocolChannelsDirty = false
    private var playDropDiagnostics = newPlayDropDiagnostics()

    val state: ClientHandshakeState
        get() = session.state

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { listener, _, _ -> begin(listener) }
        ClientPlayConnectionEvents.DISCONNECT.register { listener, _ -> disconnect(listener) }
        C2SPlayChannelEvents.REGISTER.register { listener, _, _, channels ->
            if (activeListener === listener && channels.any(clientProtocolChannels::contains)) {
                retryDeferredServerHello(listener)
            }
        }
        C2SPlayChannelEvents.UNREGISTER.register { listener, _, _, channels ->
            if (activeListener === listener && channels.any(clientProtocolChannels::contains)) {
                markProtocolChannelsUnavailable()
            }
        }
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ -> clearWorldState() }
        ClientTickEvents.END_CLIENT_TICK.register(::onClientTick)
        registerReceivers()
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

    fun pickerContext(): EmotionPickerContext? {
        val supported = state as? ClientHandshakeState.Supported ?: return null
        if (activeListener == null || supported.connectionId != activeConnectionId) {
            return null
        }
        return EmotionPickerContext(activeConnectionId, supported.policy.allowedEmotions)
    }

    fun sendSelection(listener: ClientPacketListener, emotionId: EmotionId): ClientSelectionSendResult {
        if (activeListener !== listener) {
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
        val localPlayer = Minecraft.getInstance().player ?: return ClientSelectionSendResult.NOT_CONNECTED
        if (!ClientSelectionEligibility.canPublish(
                localPlayer.isAlive,
                localPlayer.isSpectator,
                localPlayer.isInvisible,
            )
        ) {
            return ClientSelectionSendResult.PLAYER_STATE
        }
        if (activeEmotions.visibleFor(localPlayer.id, localPlayer.uuid) != null) {
            return ClientSelectionSendResult.EMOTION_ACTIVE
        }
        if (!ClientPlayNetworking.canSend(FabricEmotionSelectionPayload.TYPE)) {
            return ClientSelectionSendResult.CHANNEL_UNAVAILABLE
        }
        if (!selectionResponseGate.tryReserve(emotionId)) {
            return ClientSelectionSendResult.REQUEST_PENDING
        }
        val admitted = try {
            selectionAttemptGate.tryAdmit()
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            throw error
        }
        if (!admitted) {
            selectionResponseGate.cancelReservation()
            return ClientSelectionSendResult.REQUEST_THROTTLED
        }
        try {
            ClientPlayNetworking.send(FabricEmotionSelectionPayload(me.whish.emotify.protocol.EmotionSelection(emotionId)))
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            selectionAttemptGate.refund()
            throw error
        }
        return ClientSelectionSendResult.SENT
    }

    private fun registerReceivers() {
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricServerHelloPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.envelope)
            },
        ) {
            "Fabric server hello receiver is already registered"
        }
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricSelectionRejectedPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.rejection)
            },
        ) {
            "Fabric selection rejection receiver is already registered"
        }
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricEmotionPlayPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.play)
            },
        ) {
            "Fabric emotion play receiver is already registered"
        }
    }

    private fun begin(listener: ClientPacketListener) {
        activeConnectionId = Math.incrementExact(activeConnectionId)
        activeListener = listener
        localPlayerReference = WeakReference(Minecraft.getInstance().player)
        deferredServerHello = null
        protocolChannelsDirty = false
        helloResponseGate.begin(activeConnectionId)
        serverHelloIngressGuard.begin(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
        playIngressGuard.begin(activeConnectionId)
        playGate.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        playDropDiagnostics = newPlayDropDiagnostics()
        session.begin(activeConnectionId)

        if (supportsClientProtocol()) {
            EmotifyFabric.LOGGER.info("Emotify client handshake pending on connection {}", activeConnectionId)
        } else {
            EmotifyFabric.LOGGER.info("Emotify client handshake unavailable: optional channels are absent")
        }
    }

    private fun disconnect(listener: ClientPacketListener) {
        if (activeListener !== listener) {
            return
        }
        session.disconnect(activeConnectionId)
        activeListener = null
        localPlayerReference.clear()
        deferredServerHello = null
        protocolChannelsDirty = false
        helloResponseGate.disconnect(activeConnectionId)
        serverHelloIngressGuard.disconnect(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
        playIngressGuard.disconnect(activeConnectionId)
        playGate.disconnect(activeConnectionId)
        activeEmotions.disconnect(activeConnectionId)
    }

    private fun receive(listener: ClientPacketListener, envelope: ServerHelloEnvelope) {
        if (activeListener !== listener) {
            return
        }
        if (!supportsClientProtocol()) {
            deferredServerHello = envelope
            return
        }
        if (protocolChannelsDirty) {
            restartProtocolSession()
        }
        deferredServerHello = null
        if (!serverHelloIngressGuard.tryAdmit(activeConnectionId, envelope)) {
            return
        }
        val transition = when (envelope) {
            is ServerHelloEnvelope.Valid -> {
                if (!sendClientHelloResponse()) {
                    return
                }
                session.receiveServerHello(activeConnectionId, envelope.hello)
            }
            ServerHelloEnvelope.DuplicateEmotionIds -> session.rejectDuplicateServerCatalog(activeConnectionId)
        }
        logTransition(transition)
    }

    private fun receive(listener: ClientPacketListener, rejection: SelectionRejected) {
        if (activeListener !== listener || state !is ClientHandshakeState.Supported) {
            return
        }
        if (!selectionResponseGate.tryConsumeRejection()) {
            return
        }
        val reason = rejection.code.knownReason?.name ?: "UNKNOWN"
        EmotifyFabric.LOGGER.info(
            "Emotify selection rejected on connection {}: reason={}, reasonCode={}, retryAfterMillis={}",
            activeConnectionId,
            reason,
            rejection.code.value,
            rejection.retryAfterMillis,
        )
        val minecraft = Minecraft.getInstance()
        val message = rejection.userMessage()
        val picker = minecraft.screen as? EmotionPickerScreen
        if (picker == null) {
            minecraft.player?.displayClientMessage(message, true)
        } else {
            picker.showNotice(message)
        }
    }

    private fun receive(listener: ClientPacketListener, play: EmotionPlay) {
        if (activeListener !== listener) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (supported.connectionId != activeConnectionId || !playIngressGuard.tryAdmit(activeConnectionId)) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        val source = minecraft.level?.getEntity(play.entityId.value) as? Player ?: return
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
        if (source === localPlayer && selectionResponseGate.tryConsumeSuccess(play.emotionId)) {
            (minecraft.screen as? EmotionPickerScreen)?.selectionAccepted()
        }
        val activation = activeEmotions.activate(activeConnectionId, play)
        if (activation != EmotionActivationResult.ADDED && activation != EmotionActivationResult.REPLACED) {
            if (playDropDiagnostics.tryConsume()) {
                EmotifyFabric.LOGGER.warn(
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

    private fun onClientTick(minecraft: Minecraft) {
        val currentPlayer = minecraft.player
        if (localPlayerReference.get() !== currentPlayer) {
            localPlayerReference = WeakReference(currentPlayer)
            clearWorldState()
        }
        if (activeEmotions.size > 0) {
            activeEmotions.removeExpired()
            discardIneligibleEmotions()
        }
        logTransition(session.pollTimeout())
    }

    private fun retryDeferredServerHello(listener: ClientPacketListener) {
        if (!supportsClientProtocol()) {
            return
        }
        val deferred = deferredServerHello ?: return
        deferredServerHello = null
        receive(listener, deferred)
    }

    private fun markProtocolChannelsUnavailable() {
        protocolChannelsDirty = true
        helloResponseGate.cancelResponse(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
    }

    private fun restartProtocolSession() {
        deferredServerHello = null
        protocolChannelsDirty = false
        helloResponseGate.begin(activeConnectionId)
        serverHelloIngressGuard.begin(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
        playIngressGuard.begin(activeConnectionId)
        playGate.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        playDropDiagnostics = newPlayDropDiagnostics()
        session.begin(activeConnectionId)
        EmotifyFabric.LOGGER.info(
            "Emotify client handshake restarted after server channel removal on connection {}",
            activeConnectionId,
        )
    }

    private fun clearWorldState() {
        activeEmotions.clearWorld(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
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

    private fun sendClientHelloResponse(): Boolean {
        if (helloResponseGate.hasResponded(activeConnectionId)) {
            return true
        }
        if (activeListener !== Minecraft.getInstance().connection || !supportsClientProtocol()) {
            return false
        }
        if (!helloResponseGate.tryRespond(activeConnectionId)) {
            return false
        }
        try {
            ClientPlayNetworking.send(FabricClientHelloPayload(FabricProtocol.clientHello))
        } catch (error: RuntimeException) {
            helloResponseGate.cancelResponse(activeConnectionId)
            throw error
        }
        EmotifyFabric.LOGGER.info("Emotify client hello sent on connection {}", activeConnectionId)
        return true
    }

    private fun supportsClientProtocol(): Boolean =
        ClientPlayNetworking.canSend(FabricClientHelloPayload.TYPE) &&
            ClientPlayNetworking.canSend(FabricEmotionSelectionPayload.TYPE)

    private val clientProtocolChannels = setOf(
        FabricClientHelloPayload.TYPE.id(),
        FabricEmotionSelectionPayload.TYPE.id(),
    )

    private fun logTransition(transition: ClientHandshakeTransition) {
        when (transition) {
            ClientHandshakeTransition.SUPPORTED -> EmotifyFabric.LOGGER.info(
                "Emotify client handshake supported on connection {}",
                activeConnectionId,
            )
            ClientHandshakeTransition.POLICY_UPDATED -> EmotifyFabric.LOGGER.info(
                "Emotify client policy updated on connection {}",
                activeConnectionId,
            )
            ClientHandshakeTransition.UNSUPPORTED -> {
                activeEmotions.clearWorld(activeConnectionId)
                EmotifyFabric.LOGGER.warn(
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

    private fun newPlayDropDiagnostics(): TokenBucket = TokenBucket(
        capacity = PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND,
        timeSource = SystemMonotonicTimeSource,
    )

    private fun SelectionRejected.userMessage(): Component = when (code.knownReason) {
        SelectionRejectionReason.COOLDOWN -> Component.translatable("message.emotify.selection_cooldown")
        SelectionRejectionReason.SERVER_DISABLED,
        SelectionRejectionReason.EMOTION_DISABLED,
        -> Component.translatable("message.emotify.selection_unavailable")
        SelectionRejectionReason.PLAYER_STATE -> Component.translatable("message.emotify.player_state")
        SelectionRejectionReason.SERVER_BUSY -> Component.translatable("message.emotify.server_busy")
        null -> Component.translatable("message.emotify.selection_failed")
    }

    private const val PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY = 4
    private const val PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND = 2
}
