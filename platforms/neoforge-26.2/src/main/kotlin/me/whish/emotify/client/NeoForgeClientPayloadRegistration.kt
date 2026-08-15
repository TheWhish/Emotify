package me.whish.emotify.client

import me.whish.emotify.network.ClientPayloadReceiver
import me.whish.emotify.network.payload.CustomEmojiAssetChunkPayload
import me.whish.emotify.network.payload.CustomEmojiAssetPayload
import me.whish.emotify.network.payload.CustomEmotionPlayPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent

object NeoForgeClientPayloadRegistration {
    fun register(event: RegisterClientPayloadHandlersEvent) {
        event.register(ServerHelloPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.envelope)
        }
        event.register(SelectionRejectedPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.rejection)
        }
        event.register(EmotionPlayPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.play)
        }
        event.register(CustomEmojiAssetPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.transfer)
        }
        event.register(CustomEmojiAssetChunkPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.chunk)
        }
        event.register(CustomEmotionPlayPayload.TYPE) { payload, context ->
            ClientPayloadReceiver.receive(context.connection(), payload.play)
        }
    }
}
