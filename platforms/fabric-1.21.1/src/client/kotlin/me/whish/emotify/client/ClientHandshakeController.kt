package me.whish.emotify.client

import java.lang.ref.WeakReference
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.client.picker.ClientSelectionEligibility
import me.whish.emotify.client.picker.EmotionPickerContext
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.ClientEmotionVisibility
import me.whish.emotify.client.state.ActiveEmotion
import me.whish.emotify.client.state.ClientActiveEmotionStore
import me.whish.emotify.client.state.ClientEmotionPlayCoordinator
import me.whish.emotify.client.state.ClientEmotionPlayDisposition
import me.whish.emotify.client.state.ClientHandshakeSession
import me.whish.emotify.client.state.ClientHandshakeState
import me.whish.emotify.client.state.ClientHandshakeTransition
import me.whish.emotify.client.state.ClientHelloResponseGate
import me.whish.emotify.client.state.ClientPlayIngressGuard
import me.whish.emotify.client.state.ClientSelectionAttemptGate
import me.whish.emotify.client.state.ClientSelectionResponseGate
import me.whish.emotify.client.state.ClientSelectionSendResult
import me.whish.emotify.client.state.ClientServerHelloIngressGuard
import me.whish.emotify.client.state.ClientCustomEmojiUploadTracker
import me.whish.emotify.client.state.ClientCustomEmojiAssetIngressGuard
import me.whish.emotify.client.state.EmotionActivationResult
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.fabric.EmotifyFabric
import me.whish.emotify.fabric.network.payload.FabricClientHelloPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionSelectionPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionSelectionPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetChunkPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionPlayPayload
import me.whish.emotify.fabric.runtime.FabricProtocol
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembler
import me.whish.emotify.wire.v1.CustomEmojiAssetAssemblyResult
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
        EmotifyProtocolFeatures.registry,
    )
    private val helloResponseGate = ClientHelloResponseGate()
    private val serverHelloIngressGuard = ClientServerHelloIngressGuard(SystemMonotonicTimeSource)
    private val selectionResponseGate = ClientSelectionResponseGate()
    private val selectionAttemptGate = ClientSelectionAttemptGate()
    private val playIngressGuard = ClientPlayIngressGuard(SystemMonotonicTimeSource)
    private val playCoordinator = ClientEmotionPlayCoordinator()
    private val activeEmotions = ClientActiveEmotionStore(SystemMonotonicTimeSource, EmotionPresentationRegistry::contains)
    private val customUploads = ClientCustomEmojiUploadTracker()
    private val customAssetIngress = ClientCustomEmojiAssetIngressGuard(SystemMonotonicTimeSource)
    private val customAssetAssembler = CustomEmojiAssetAssembler()

    private var activeListener: ClientPacketListener? = null
    private var activeConnectionId = 0L
    private var localPlayerReference = WeakReference<LocalPlayer>(null)
    private var deferredServerHello: ServerHelloEnvelope? = null
    private var protocolChannelsDirty = false
    private var playDropDiagnostics = newDropDiagnostics()
    private var customAssetDropDiagnostics = newDropDiagnostics()

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
        if (activeEmotions.find(player.id, player.uuid) == null) return null
        if (!isEligibleForEmotion(player)) return null
        val active = activeEmotions.visibleFor(player.id, player.uuid) ?: return null
        return knownEmotionOrDiscard(player, active)
    }

    fun shouldHideNameTagFor(player: AbstractClientPlayer): Boolean {
        val active = activeEmotions.find(player.id, player.uuid) ?: return false
        if (!isEligibleForEmotion(player)) return false
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
        if (
            activeEmotions.visibleFor(localPlayer.id, localPlayer.uuid) != null
        ) {
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

    fun sendCustomSelection(emotionId: EmotionId): ClientSelectionSendResult {
        val supported = state as? ClientHandshakeState.Supported
            ?: return ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE
        if (activeListener == null || supported.connectionId != activeConnectionId) {
            return ClientSelectionSendResult.NOT_CONNECTED
        }
        val localPlayer = Minecraft.getInstance().player ?: return ClientSelectionSendResult.NOT_CONNECTED
        if (!EmotifyProtocolFeatures.supportsCustomEmojiSharing(supported.negotiated.features)) {
            return ClientSelectionSendResult.CUSTOM_EMOJIS_UNSUPPORTED
        }
        if (!ClientSelectionEligibility.canPublish(localPlayer.isAlive, localPlayer.isSpectator, localPlayer.isInvisible)) {
            return ClientSelectionSendResult.PLAYER_STATE
        }
        if (activeEmotions.visibleFor(localPlayer.id, localPlayer.uuid) != null) {
            return ClientSelectionSendResult.EMOTION_ACTIVE
        }
        if (!ClientPlayNetworking.canSend(FabricCustomEmotionSelectionPayload.TYPE)) {
            return ClientSelectionSendResult.CHANNEL_UNAVAILABLE
        }
        val asset = CustomEmojiRegistry.asset(emotionId) ?: return ClientSelectionSendResult.CUSTOM_EMOJI_MISSING
        val descriptor = CustomEmojiRegistry.descriptor(emotionId) ?: return ClientSelectionSendResult.CUSTOM_EMOJI_MISSING
        when (customUploads.rejection(activeConnectionId, asset.id)) {
            SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED -> return ClientSelectionSendResult.CUSTOM_EMOJIS_DISABLED
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE -> return ClientSelectionSendResult.CUSTOM_EMOJI_TOO_LARGE
            else -> Unit
        }
        if (asset.isAnimated && !EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(supported.negotiated.features)) {
            return ClientSelectionSendResult.CUSTOM_EMOJIS_UNSUPPORTED
        }
        val lossless = asset.pixels.size > LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE
        val transferChunks = if (lossless) {
            CustomEmojiRegistry.transferChunks(emotionId)
                ?: return ClientSelectionSendResult.CUSTOM_EMOJI_MISSING
        } else {
            emptyList()
        }
        if (
            lossless && (
                !EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(supported.negotiated.features) ||
                    !ClientPlayNetworking.canSend(FabricCustomEmojiAssetChunkPayload.TYPE)
                )
        ) {
            return ClientSelectionSendResult.CUSTOM_EMOJIS_UNSUPPORTED
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
        val requiresUpload = customUploads.requiresUpload(activeConnectionId, asset.id)
        if (requiresUpload == null) {
            selectionResponseGate.cancelReservation()
            selectionAttemptGate.refund()
            return ClientSelectionSendResult.NOT_CONNECTED
        }
        val selection = if (lossless) {
            CustomEmotionSelection(asset.id, null, descriptor)
        } else {
            customUploads.prepare(activeConnectionId, asset, descriptor)
                ?: run {
                    selectionResponseGate.cancelReservation()
                    selectionAttemptGate.refund()
                    return ClientSelectionSendResult.NOT_CONNECTED
                }
        }
        try {
            if (lossless && requiresUpload) {
                transferChunks.forEach { chunk ->
                    ClientPlayNetworking.send(FabricCustomEmojiAssetChunkPayload(chunk))
                }
                customUploads.markUploaded(activeConnectionId, asset.id)
            }
            ClientPlayNetworking.send(FabricCustomEmotionSelectionPayload(selection))
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
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricCustomEmojiAssetPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.transfer)
            },
        ) {
            "Fabric custom emoji asset receiver is already registered"
        }
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricCustomEmojiAssetChunkPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.chunk)
            },
        ) {
            "Fabric custom emoji asset chunk receiver is already registered"
        }
        check(
            ClientPlayNetworking.registerGlobalReceiver(FabricCustomEmotionPlayPayload.TYPE) { payload, context ->
                receive(context.player().connection, payload.play)
            },
        ) {
            "Fabric custom emotion play receiver is already registered"
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
        playCoordinator.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        customUploads.begin(activeConnectionId)
        customAssetIngress.begin(activeConnectionId)
        customAssetAssembler.reset()
        RemoteCustomEmojiRegistry.begin(activeConnectionId)
        playDropDiagnostics = newDropDiagnostics()
        customAssetDropDiagnostics = newDropDiagnostics()
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
        playCoordinator.disconnect(activeConnectionId)
        activeEmotions.disconnect(activeConnectionId)
        customUploads.disconnect(activeConnectionId)
        customAssetIngress.disconnect(activeConnectionId)
        customAssetAssembler.reset()
        RemoteCustomEmojiRegistry.disconnect(activeConnectionId)
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
                customUploads.clearRejections(activeConnectionId)
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
        val rejectedEmotion = selectionResponseGate.consumeRejection() ?: run {
            return
        }
        when (rejection.code.knownReason) {
            SelectionRejectionReason.CUSTOM_ASSET_MISSING ->
                CustomEmojiId.parse(rejectedEmotion)?.let { id -> customUploads.forget(activeConnectionId, id) }
            SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED ->
                customUploads.rejectAll(activeConnectionId, SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED)
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE ->
                CustomEmojiId.parse(rejectedEmotion)?.let { id ->
                    customUploads.reject(activeConnectionId, id, SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE)
                }
            else -> Unit
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
        val settings = EmotifyClientConfig.settings()
        val disposition = playCoordinator.evaluate(
            activeConnectionId,
            supported.policy.allowedEmotions,
            play,
            source.id,
            source.uuid,
            !source.isInvisibleTo(localPlayer),
            source === localPlayer,
            source.gameProfile.name,
            settings,
        )
        if (
            selectionResponseGate.tryConsumeAcceptedPlay(
                play.emotionId,
                source === localPlayer,
                disposition,
            )
        ) {
            (minecraft.screen as? EmotionPickerScreen)?.selectionAccepted()
        }
        when (disposition) {
            ClientEmotionPlayDisposition.REJECTED -> return
            ClientEmotionPlayDisposition.HIDDEN -> {
                activeEmotions.discard(source.id, source.uuid)
                return
            }
            ClientEmotionPlayDisposition.VISIBLE -> Unit
        }
        val activation = activeEmotions.activate(activeConnectionId, play)
        if (activation == EmotionActivationResult.ADDED || activation == EmotionActivationResult.REPLACED) {
            EmotionSoundEngine.play(play.emotionId, source, settings.soundVolumePercent)
        } else {
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

    private fun receive(listener: ClientPacketListener, transfer: CustomEmojiTransfer) {
        if (activeListener !== listener || !customAssetIngress.tryAdmit(activeConnectionId)) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (!EmotifyProtocolFeatures.supportsCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        if (
            transfer.asset.isAnimated &&
            !EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(supported.negotiated.features)
        ) {
            return
        }
        RemoteCustomEmojiRegistry.register(activeConnectionId, transfer.asset)
    }

    private fun receive(listener: ClientPacketListener, chunk: CustomEmojiAssetChunk) {
        if (activeListener !== listener || !customAssetIngress.tryAdmit(activeConnectionId)) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (!EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        val asset = when (
            val result = customAssetAssembler.tryAcceptAssembly(chunk, System.nanoTime() / 1_000_000L)
        ) {
            CustomEmojiAssetAssemblyResult.Pending -> return
            is CustomEmojiAssetAssemblyResult.Completed -> result.assembly.asset
            is CustomEmojiAssetAssemblyResult.Rejected -> {
                if (customAssetDropDiagnostics.tryConsume()) {
                    EmotifyFabric.LOGGER.warn(
                        "Rejected malformed custom emoji transfer on connection {}: {}",
                        activeConnectionId,
                        result.violation,
                    )
                }
                return
            }
        }
        if (asset.isAnimated && !EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        RemoteCustomEmojiRegistry.register(activeConnectionId, asset)
    }

    private fun receive(listener: ClientPacketListener, play: CustomEmotionPlay) {
        if (activeListener !== listener || !playIngressGuard.tryAdmit(activeConnectionId)) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (!EmotifyProtocolFeatures.supportsCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        val source = minecraft.level?.getEntity(play.entityId.value) as? Player ?: return
        val settings = EmotifyClientConfig.settings()
        val disposition = playCoordinator.evaluateCustom(
            activeConnectionId,
            play,
            source.id,
            source.uuid,
            !source.isInvisibleTo(localPlayer),
            source === localPlayer,
            source.gameProfile.name,
            settings,
        )
        val localSource = source === localPlayer
        val selectionAccepted = selectionResponseGate.tryConsumeAcceptedPlay(
            play.customEmojiId.emotionId,
            localSource,
            disposition,
        )
        if (localSource && disposition != ClientEmotionPlayDisposition.REJECTED) {
            customUploads.markUploaded(activeConnectionId, play.customEmojiId)
        }
        if (selectionAccepted) {
            (minecraft.screen as? EmotionPickerScreen)?.selectionAccepted()
        }
        when (disposition) {
            ClientEmotionPlayDisposition.REJECTED -> return
            ClientEmotionPlayDisposition.HIDDEN -> {
                activeEmotions.discard(source.id, source.uuid)
                return
            }
            ClientEmotionPlayDisposition.VISIBLE -> Unit
        }
        if (
            CustomEmojiRegistry.find(play.customEmojiId.emotionId) == null &&
            RemoteCustomEmojiRegistry.find(play.customEmojiId.emotionId) == null
        ) {
            if (playDropDiagnostics.tryConsume()) {
                EmotifyFabric.LOGGER.warn(
                    "Emotify custom play dropped after acknowledgement because its presentation is unavailable: connection={}, emotion={}, entityId={}, sequence={}",
                    activeConnectionId,
                    play.customEmojiId.emotionId,
                    play.entityId.value,
                    play.sequence.value,
                )
            }
            return
        }
        val basePlay = play.asEmotionPlay()
        val activation = activeEmotions.activateCustom(activeConnectionId, play)
        if (activation == EmotionActivationResult.ADDED || activation == EmotionActivationResult.REPLACED) {
            EmotionSoundEngine.play(basePlay.emotionId, source, settings.soundVolumePercent)
        }
    }

    fun applySettings(settings: ClientSettingsSnapshot) {
        EmotifyClientConfig.saveSettings(settings)
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player
        val level = minecraft.level
        activeEmotions.discardIf { active ->
            val localSource = active.sourceUuid == localPlayer?.uuid
            val source = level?.getEntity(active.entityId.value) as? Player
            val sourceName = source
                ?.takeIf { candidate -> candidate.uuid == active.sourceUuid }
                ?.gameProfile
                ?.name
                .orEmpty()
            !ClientEmotionVisibility.allowsActive(
                localSource,
                active.sourceUuid,
                sourceName,
                active.emotionId,
                settings,
            )
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
        playCoordinator.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        customUploads.begin(activeConnectionId)
        customAssetIngress.begin(activeConnectionId)
        customAssetAssembler.reset()
        RemoteCustomEmojiRegistry.begin(activeConnectionId)
        playDropDiagnostics = newDropDiagnostics()
        customAssetDropDiagnostics = newDropDiagnostics()
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
            val source = level.getEntity(active.entityId.value) as? Player ?: return@discardIf true
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
        if (EmotionPresentationRegistry.find(active.emotionId) != null) {
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
        FabricCustomEmotionSelectionPayload.TYPE.id(),
    )

    private fun logTransition(transition: ClientHandshakeTransition) {
        when (transition) {
            ClientHandshakeTransition.SUPPORTED -> logSupportedHandshake()
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

    private fun logSupportedHandshake() {
        val supported = checkNotNull(state as? ClientHandshakeState.Supported) {
            "Supported handshake transition without supported state"
        }
        EmotifyFabric.LOGGER.info(
            "Emotify client handshake supported on connection {}: protocol={}.{}, features=0x{}",
            activeConnectionId,
            supported.negotiated.version.major,
            supported.negotiated.version.minor,
            java.lang.Long.toUnsignedString(supported.negotiated.features.bits, 16),
        )
    }

    private fun newDropDiagnostics(): TokenBucket = TokenBucket(
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
        SelectionRejectionReason.CUSTOM_ASSET_MISSING -> Component.translatable("message.emotify.custom_asset_missing")
        SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED -> Component.translatable(
            "message.emotify.custom_emojis_disabled",
        )
        SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE -> Component.translatable(
            "message.emotify.custom_emoji_too_large",
        )
        null -> Component.translatable("message.emotify.selection_failed")
    }

    private const val PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY = 4
    private const val PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND = 2
    private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
}
