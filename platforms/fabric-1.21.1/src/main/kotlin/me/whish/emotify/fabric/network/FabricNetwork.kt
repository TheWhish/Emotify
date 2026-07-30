package me.whish.emotify.fabric.network

import me.whish.emotify.fabric.network.payload.FabricClientHelloPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricEmotionSelectionPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.runtime.FabricProtocol
import me.whish.emotify.fabric.server.FabricServerConnectionRegistry
import me.whish.emotify.fabric.server.FabricServerRuntime
import me.whish.emotify.server.core.GlobalSelectionIngressAdmission
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object FabricNetwork {
    fun register() {
        PayloadTypeRegistry.playC2S().register(
            FabricClientHelloPayload.TYPE,
            FabricClientHelloPayload.STREAM_CODEC,
        )
        PayloadTypeRegistry.playC2S().register(
            FabricEmotionSelectionPayload.TYPE,
            FabricEmotionSelectionPayload.STREAM_CODEC,
        )
        PayloadTypeRegistry.playS2C().register(
            FabricServerHelloPayload.TYPE,
            FabricServerHelloPayload.STREAM_CODEC,
        )
        PayloadTypeRegistry.playS2C().register(
            FabricEmotionPlayPayload.TYPE,
            FabricEmotionPlayPayload.STREAM_CODEC,
        )
        PayloadTypeRegistry.playS2C().register(
            FabricSelectionRejectedPayload.TYPE,
            FabricSelectionRejectedPayload.STREAM_CODEC,
        )

        check(ServerPlayNetworking.registerGlobalReceiver(FabricClientHelloPayload.TYPE, ::receiveClientHello)) {
            "Fabric client hello receiver is already registered"
        }
        check(
            ServerPlayNetworking.registerGlobalReceiver(
                FabricEmotionSelectionPayload.TYPE,
                ::receiveEmotionSelection,
            ),
        ) {
            "Fabric emotion selection receiver is already registered"
        }
    }

    private fun receiveClientHello(
        payload: FabricClientHelloPayload,
        context: ServerPlayNetworking.Context,
    ) {
        val player = context.player()
        if (context.server().playerList.getPlayer(player.uuid) !== player) {
            return
        }
        val state = FabricServerConnectionRegistry.current(player.uuid) ?: return
        if (!state.belongsTo(player.connection)) {
            return
        }
        if (!state.clientHelloGuard.evaluate(payload.hello).shouldForward) {
            return
        }
        FabricServerRuntime.receiveClientHello(context.server(), player.uuid, state.connectionId, payload.hello)
    }

    private fun receiveEmotionSelection(
        payload: FabricEmotionSelectionPayload,
        context: ServerPlayNetworking.Context,
    ) {
        val player = context.player()
        if (context.server().playerList.getPlayer(player.uuid) !== player) {
            return
        }
        val state = FabricServerConnectionRegistry.current(player.uuid) ?: return
        if (!state.belongsTo(player.connection)) {
            return
        }
        val emotionId = payload.selection.emotionId
        if (!state.selectionIngressGuard.shouldForward(emotionId, FabricProtocol.serverHello.emotionCatalog)) {
            return
        }
        val lease = when (val admission = FabricServerRuntime.tryAcquireSelectionIngress()) {
            is GlobalSelectionIngressAdmission.Admitted -> admission.lease
            GlobalSelectionIngressAdmission.OutstandingLimitReached,
            GlobalSelectionIngressAdmission.RateLimited,
            -> return
        }
        try {
            FabricServerRuntime.select(context.server(), player, state, emotionId)
        } finally {
            lease.release()
        }
    }
}
