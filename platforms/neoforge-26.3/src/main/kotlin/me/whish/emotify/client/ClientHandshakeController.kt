package me.whish.emotify.client

import java.util.ArrayDeque
import me.whish.emotify.Emotify
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.client.custom.RemoteCustomAssetAdmission
import me.whish.emotify.client.custom.RemoteCustomAssetDecodePipeline
import me.whish.emotify.client.custom.RemoteCustomAssetDecodeResult
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
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.EmotifyChannels
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.CustomEmotionSelectionPayload
import me.whish.emotify.network.payload.CustomEmojiAssetChunkPayload
import me.whish.emotify.runtime.EmotifyProtocol
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.CustomEmotionSelection
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
        BuiltInEmotionCatalog.catalog,
        SystemMonotonicTimeSource,
        EmotifyProtocolFeatures.registry,
    )

    private var activeConnection: Connection? = null
    private var activeConnectionId = 0L
    private val helloResponseGate = ClientHelloResponseGate()
    private val serverHelloIngressGuard = ClientServerHelloIngressGuard(SystemMonotonicTimeSource)
    private val selectionResponseGate = ClientSelectionResponseGate()
    private val selectionAttemptGate = ClientSelectionAttemptGate()
    private val playIngressGuard = ClientPlayIngressGuard(SystemMonotonicTimeSource)
    private val playCoordinator = ClientEmotionPlayCoordinator()
    private val activeEmotions = ClientActiveEmotionStore(SystemMonotonicTimeSource, EmotionPresentationRegistry::contains)
    private val customUploads = ClientCustomEmojiUploadTracker()
    private val customAssetIngress = ClientCustomEmojiAssetIngressGuard(SystemMonotonicTimeSource)
    private val customAssetDecoder = RemoteCustomAssetDecodePipeline<RemoteCustomEmojiRegistry.PreparedRemoteCustomEmoji>(
        completionExecutor = { task -> Minecraft.getInstance().execute(task) },
        completionListener = ::completeCustomAssetDecode,
        preparer = { assembly -> RemoteCustomEmojiRegistry.prepare(assembly.asset) },
        preparedDisposer = RemoteCustomEmojiRegistry::discard,
    )
    private val deferredCustomPlays = ArrayDeque<CustomEmotionPlay>(MAXIMUM_DEFERRED_CUSTOM_PLAYS)
    private var playDropDiagnostics = newDropDiagnostics()
    private var customAssetDropDiagnostics = newDropDiagnostics()

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

    fun pickerContext(): EmotionPickerContext? {
        val supported = state as? ClientHandshakeState.Supported ?: return null
        if (activeConnection == null || supported.connectionId != activeConnectionId) {
            return null
        }
        return EmotionPickerContext(activeConnectionId, supported.policy.allowedEmotions)
    }

    fun receive(connection: Connection, envelope: ServerHelloEnvelope) {
        if (
            activeConnection !== connection ||
            !serverHelloIngressGuard.tryAdmit(activeConnectionId, envelope)
        ) {
            return
        }
        val transition = when (envelope) {
            is ServerHelloEnvelope.Valid -> {
                customUploads.clearRejections(activeConnectionId)
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
        val rejectedEmotion = selectionResponseGate.consumeRejection() ?: run {
            return
        }
        when (rejection.code.knownReason) {
            SelectionRejectionReason.CUSTOM_ASSET_MISSING ->
                CustomEmojiId.parse(rejectedEmotion)?.let { id ->
                    customUploads.forget(activeConnectionId, id)
                }
            SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED ->
                customUploads.rejectAll(activeConnectionId, SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED)
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE ->
                CustomEmojiId.parse(rejectedEmotion)?.let { id ->
                    customUploads.reject(activeConnectionId, id, SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE)
                }
            else -> Unit
        }
        val reason = rejection.code.knownReason?.name ?: "UNKNOWN"
        Emotify.LOGGER.info(
            "Emotify selection rejected on connection {}: reason={}, reasonCode={}, retryAfterMillis={}",
            activeConnectionId,
            reason,
            rejection.code.value,
            rejection.retryAfterMillis,
        )
        val minecraft = Minecraft.getInstance()
        val message = rejection.userMessage()
        val picker = minecraft.gui.screen() as? EmotionPickerScreen
        if (picker == null) {
            minecraft.player?.sendOverlayMessage(message)
        } else {
            picker.showNotice(message)
        }
    }

    fun receive(connection: Connection, play: EmotionPlay) {
        if (activeConnection !== connection) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (supported.connectionId != activeConnectionId) {
            return
        }
        if (!playIngressGuard.tryAdmit(activeConnectionId)) {
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
            (minecraft.gui.screen() as? EmotionPickerScreen)?.selectionAccepted()
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

    fun receive(connection: Connection, transfer: CustomEmojiTransfer) {
        if (activeConnection !== connection || !customAssetIngress.tryAdmit(activeConnectionId)) {
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

    fun receive(connection: Connection, chunk: CustomEmojiAssetChunk) {
        if (activeConnection !== connection || !customAssetIngress.tryAdmit(activeConnectionId)) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (!EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        when (customAssetDecoder.submit(activeConnectionId, chunk)) {
            RemoteCustomAssetAdmission.ACCEPTED -> Unit
            RemoteCustomAssetAdmission.INACTIVE_CONNECTION -> Unit
            RemoteCustomAssetAdmission.SATURATED -> {
                if (customAssetDropDiagnostics.tryConsume()) {
                    Emotify.LOGGER.warn(
                        "Rejected custom emoji transfer on connection {} because the decode queue is saturated",
                        activeConnectionId,
                    )
                }
            }
            RemoteCustomAssetAdmission.CLOSED -> Emotify.LOGGER.error(
                "Rejected custom emoji transfer because the decode pipeline is closed",
            )
        }
    }

    fun receive(connection: Connection, play: CustomEmotionPlay) {
        if (activeConnection !== connection) {
            return
        }
        val supported = state as? ClientHandshakeState.Supported ?: return
        if (!EmotifyProtocolFeatures.supportsCustomEmojiSharing(supported.negotiated.features)) {
            return
        }
        if (!playIngressGuard.tryAdmit(activeConnectionId)) {
            return
        }
        if (!hasCustomPresentation(play.customEmojiId)) {
            if (
                customAssetDecoder.isAwaiting(activeConnectionId, play.customEmojiId) &&
                deferredCustomPlays.size < MAXIMUM_DEFERRED_CUSTOM_PLAYS
            ) {
                deferredCustomPlays.addLast(play)
            } else {
                logUnavailableCustomPlay(play)
            }
            return
        }
        processCustomPlay(play)
    }

    private fun processCustomPlay(play: CustomEmotionPlay) {
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
            (minecraft.gui.screen() as? EmotionPickerScreen)?.selectionAccepted()
        }
        when (disposition) {
            ClientEmotionPlayDisposition.REJECTED -> return
            ClientEmotionPlayDisposition.HIDDEN -> {
                activeEmotions.discard(source.id, source.uuid)
                return
            }
            ClientEmotionPlayDisposition.VISIBLE -> Unit
        }
        val basePlay = play.asEmotionPlay()
        val activation = activeEmotions.activateCustom(activeConnectionId, play)
        if (activation == EmotionActivationResult.ADDED || activation == EmotionActivationResult.REPLACED) {
            EmotionSoundEngine.play(basePlay.emotionId, source, settings.soundVolumePercent)
        }
    }

    @Suppress("unused")
    fun applySettings(settings: ClientSettingsSnapshot) {
        EmotifyClientConfig.saveSettings(settings)
        discardFilteredEmotions(settings)
    }

    private fun discardFilteredEmotions(settings: ClientSettingsSnapshot) {
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

    @Suppress("unused")
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
        if (!listener.hasChannel(EmotionSelectionPayload.TYPE)) {
            return ClientSelectionSendResult.CHANNEL_UNAVAILABLE
        }
        if (!selectionResponseGate.tryReserve(emotionId)) {
            return ClientSelectionSendResult.REQUEST_PENDING
        }
        val attemptAdmitted = try {
            selectionAttemptGate.tryAdmit()
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            throw error
        }
        if (!attemptAdmitted) {
            selectionResponseGate.cancelReservation()
            return ClientSelectionSendResult.REQUEST_THROTTLED
        }

        try {
            listener.send(EmotionSelectionPayload(EmotionSelection(emotionId)))
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            selectionAttemptGate.refund()
            throw error
        }
        return ClientSelectionSendResult.SENT
    }

    @Suppress("unused")
    fun sendCustomSelection(emotionId: EmotionId): ClientSelectionSendResult {
        val supported = state as? ClientHandshakeState.Supported
            ?: return ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE
        if (activeConnection == null || supported.connectionId != activeConnectionId) {
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
        val listener = Minecraft.getInstance().connection ?: return ClientSelectionSendResult.NOT_CONNECTED
        if (!listener.hasChannel(CustomEmotionSelectionPayload.TYPE)) {
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
                    !listener.hasChannel(CustomEmojiAssetChunkPayload.TYPE)
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
                    listener.send(CustomEmojiAssetChunkPayload(chunk))
                }
                customUploads.commitProvisionalUpload(activeConnectionId, asset.id) {
                    listener.send(CustomEmotionSelectionPayload(selection))
                }
            } else {
                listener.send(CustomEmotionSelectionPayload(selection))
            }
        } catch (error: RuntimeException) {
            selectionResponseGate.cancelReservation()
            selectionAttemptGate.refund()
            throw error
        }
        return ClientSelectionSendResult.SENT
    }

    private fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        activeConnectionId = Math.incrementExact(activeConnectionId)
        activeConnection = event.connection
        helloResponseGate.begin(activeConnectionId)
        serverHelloIngressGuard.begin(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
        playIngressGuard.begin(activeConnectionId)
        playCoordinator.begin(activeConnectionId)
        activeEmotions.begin(activeConnectionId)
        customUploads.begin(activeConnectionId)
        customAssetIngress.begin(activeConnectionId)
        RemoteCustomEmojiRegistry.begin(activeConnectionId)
        customAssetDecoder.begin(activeConnectionId)
        deferredCustomPlays.clear()
        playDropDiagnostics = newDropDiagnostics()
        customAssetDropDiagnostics = newDropDiagnostics()
        session.begin(activeConnectionId)

        val listener = event.player.connection
        val supportsHandshake = EmotifyChannels.serverCanReceiveClientPayloads { type -> listener.hasChannel(type) }
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
        serverHelloIngressGuard.disconnect(activeConnectionId)
        selectionResponseGate.reset()
        selectionAttemptGate.reset()
        playIngressGuard.disconnect(activeConnectionId)
        playCoordinator.disconnect(activeConnectionId)
        activeEmotions.disconnect(activeConnectionId)
        customUploads.disconnect(activeConnectionId)
        customAssetIngress.disconnect(activeConnectionId)
        customAssetDecoder.disconnect(activeConnectionId)
        deferredCustomPlays.clear()
        RemoteCustomEmojiRegistry.disconnect(activeConnectionId)
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
        selectionAttemptGate.reset()
        deferredCustomPlays.clear()
    }

    private fun completeCustomAssetDecode(
        connectionId: Long,
        customEmojiId: CustomEmojiId,
        result: RemoteCustomAssetDecodeResult<RemoteCustomEmojiRegistry.PreparedRemoteCustomEmoji>,
    ) {
        when (result) {
            is RemoteCustomAssetDecodeResult.Prepared -> {
                val supported = state as? ClientHandshakeState.Supported
                val asset = result.value.asset
                if (
                    connectionId != activeConnectionId ||
                    supported == null ||
                    !EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(supported.negotiated.features) ||
                    asset.isAnimated &&
                    !EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(supported.negotiated.features)
                ) {
                    RemoteCustomEmojiRegistry.discard(result.value)
                    discardDeferredCustomPlays(customEmojiId)
                    return
                }
                if (RemoteCustomEmojiRegistry.register(connectionId, result.value)) {
                    drainDeferredCustomPlays(customEmojiId)
                } else {
                    discardDeferredCustomPlays(customEmojiId)
                }
            }
            is RemoteCustomAssetDecodeResult.Rejected -> {
                discardDeferredCustomPlays(customEmojiId)
                if (customAssetDropDiagnostics.tryConsume()) {
                    Emotify.LOGGER.warn(
                        "Rejected malformed custom emoji transfer on connection {}: {}",
                        connectionId,
                        result.violation,
                    )
                }
            }
            is RemoteCustomAssetDecodeResult.Failed -> {
                discardDeferredCustomPlays(customEmojiId)
                Emotify.LOGGER.error(
                    "Failed to prepare custom emoji transfer on connection {}",
                    connectionId,
                    result.failure,
                )
            }
            RemoteCustomAssetDecodeResult.Abandoned -> discardDeferredCustomPlays(customEmojiId)
        }
    }

    private fun hasCustomPresentation(customEmojiId: CustomEmojiId): Boolean =
        CustomEmojiRegistry.find(customEmojiId.emotionId) != null ||
            RemoteCustomEmojiRegistry.find(customEmojiId.emotionId) != null

    private fun drainDeferredCustomPlays(customEmojiId: CustomEmojiId) {
        val iterator = deferredCustomPlays.iterator()
        while (iterator.hasNext()) {
            val play = iterator.next()
            if (play.customEmojiId == customEmojiId) {
                iterator.remove()
                processCustomPlay(play)
            }
        }
    }

    private fun discardDeferredCustomPlays(customEmojiId: CustomEmojiId) {
        deferredCustomPlays.removeIf { play -> play.customEmojiId == customEmojiId }
    }

    private fun logUnavailableCustomPlay(play: CustomEmotionPlay) {
        if (playDropDiagnostics.tryConsume()) {
            Emotify.LOGGER.warn(
                "Emotify custom play dropped after acknowledgement because its presentation is unavailable: connection={}, emotion={}, entityId={}, sequence={}",
                activeConnectionId,
                play.customEmojiId.emotionId,
                play.entityId.value,
                play.sequence.value,
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
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
            val source = level.getEntity(active.entityId.value) as? Player ?: return@discardIf true
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
            ClientHandshakeTransition.SUPPORTED -> logSupportedHandshake()
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

    private fun logSupportedHandshake() {
        val supported = checkNotNull(state as? ClientHandshakeState.Supported) {
            "Supported handshake transition without supported state"
        }
        Emotify.LOGGER.info(
            "Emotify client handshake supported on connection {}: protocol={}.{}, features=0x{}",
            activeConnectionId,
            supported.negotiated.version.major,
            supported.negotiated.version.minor,
            java.lang.Long.toUnsignedString(supported.negotiated.features.bits, 16),
        )
    }

    private fun sendClientHelloResponse(connection: Connection): Boolean {
        if (helloResponseGate.hasResponded(activeConnectionId)) {
            return true
        }
        val listener = Minecraft.getInstance().connection ?: return false
        if (
            listener.connection !== connection ||
            !EmotifyChannels.serverCanReceiveClientPayloads { type -> listener.hasChannel(type) }
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

    private fun newDropDiagnostics(): TokenBucket = TokenBucket(
        capacity = PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND,
        timeSource = SystemMonotonicTimeSource,
    )

    private const val PLAY_DROP_DIAGNOSTIC_BURST_CAPACITY = 4
    private const val PLAY_DROP_DIAGNOSTIC_REFILL_TOKENS_PER_SECOND = 2
    private const val LEGACY_MAXIMUM_CUSTOM_EMOJI_SIZE = 16
    private const val MAXIMUM_DEFERRED_CUSTOM_PLAYS = 16

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
}
