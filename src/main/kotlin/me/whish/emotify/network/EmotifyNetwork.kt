package me.whish.emotify.network

import me.whish.emotify.Emotify
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.protocol.EmotifyProtocol
import me.whish.emotify.server.ClientHelloIngressGuard
import me.whish.emotify.server.SelectionIngressGuard
import me.whish.emotify.server.ServerHandshakeService
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.HandlerThread

object EmotifyNetwork {
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener(::registerPayloads)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val mainRegistrar = event.registrar(EmotifyProtocol.TRANSPORT_VERSION).optional()

        mainRegistrar.playToClient(
            ServerHelloPayload.TYPE,
            ServerHelloPayload.STREAM_CODEC,
            ::receiveServerHello,
        )
        mainRegistrar.playToClient(
            SelectionRejectedPayload.TYPE,
            SelectionRejectedPayload.STREAM_CODEC,
            ::receiveSelectionRejected,
        )
        mainRegistrar.playToClient(
            EmotionPlayPayload.TYPE,
            EmotionPlayPayload.STREAM_CODEC,
            ::receiveEmotionPlay,
        )

        val networkRegistrar = mainRegistrar.executesOn(HandlerThread.NETWORK)
        networkRegistrar.playToServer(
            ClientHelloPayload.TYPE,
            ClientHelloPayload.STREAM_CODEC,
            ::receiveClientHello,
        )
        networkRegistrar.playToServer(
            EmotionSelectionPayload.TYPE,
            EmotionSelectionPayload.STREAM_CODEC,
            ::receiveEmotionSelection,
        )
    }

    private fun receiveServerHello(payload: ServerHelloPayload, context: IPayloadContext) {
        ClientPayloadReceiver.receive(context.connection(), payload.envelope)
    }

    private fun receiveSelectionRejected(payload: SelectionRejectedPayload, context: IPayloadContext) {
        ClientPayloadReceiver.receive(context.connection(), payload.rejection)
    }

    private fun receiveEmotionPlay(payload: EmotionPlayPayload, context: IPayloadContext) {
        ClientPayloadReceiver.receive(context.connection(), payload.play)
    }

    private fun receiveClientHello(payload: ClientHelloPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val connection = context.connection()
        val connectionId = connection.channel().attr(ConnectionAttributes.serverConnectionId).get() ?: return
        val guardAttribute = connection.channel().attr(ConnectionAttributes.clientHelloGuard)
        val existingGuard = guardAttribute.get()
        val guard = existingGuard ?: ClientHelloIngressGuard().let { created ->
            guardAttribute.setIfAbsent(created) ?: created
        }
        if (!guard.evaluate(payload.hello).shouldForward) {
            return
        }

        val playerId = player.uuid
        val server = player.server
        context.enqueueWork(Runnable {
            try {
                ServerHandshakeService.receive(server, playerId, connectionId, payload.hello)
            } catch (exception: RuntimeException) {
                Emotify.LOGGER.error(
                    "Failed to process Emotify client hello for player {} on connection {}",
                    playerId,
                    connectionId,
                    exception,
                )
                throw exception
            }
        })
    }

    private fun receiveEmotionSelection(payload: EmotionSelectionPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val connection = context.connection()
        val connectionId = connection.channel().attr(ConnectionAttributes.serverConnectionId).get() ?: return
        val guardAttribute = connection.channel().attr(ConnectionAttributes.selectionIngressGuard)
        val existingGuard = guardAttribute.get()
        val guard = existingGuard ?: SelectionIngressGuard(SystemMonotonicTimeSource).let { created ->
            guardAttribute.setIfAbsent(created) ?: created
        }
        val emotionId = payload.selection.emotionId
        if (!guard.tryReserveMainThreadTask(emotionId, EmotifyProtocol.serverHello.emotionCatalog)) {
            return
        }

        val playerId = player.uuid
        val server = player.server
        try {
            context.enqueueWork(Runnable {
                try {
                    ServerHandshakeService.select(server, playerId, connectionId, emotionId)
                } catch (exception: RuntimeException) {
                    Emotify.LOGGER.error(
                        "Failed to process Emotify selection {} for player {} on connection {}",
                        emotionId,
                        playerId,
                        connectionId,
                        exception,
                    )
                    throw exception
                } finally {
                    guard.releaseMainThreadTask()
                }
            })
        } catch (exception: RuntimeException) {
            guard.releaseMainThreadTask()
            throw exception
        }
    }
}
