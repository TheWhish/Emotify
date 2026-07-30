package me.whish.emotify.protocol

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.ProtocolCapabilities

data class ServerHello(
    val capabilities: ProtocolCapabilities,
    val cooldownMillis: Int,
    val emotionCatalog: EmotionCatalog,
) {
    init {
        require(cooldownMillis in MIN_COOLDOWN_MILLIS..MAX_COOLDOWN_MILLIS) {
            "Server cooldown must be between $MIN_COOLDOWN_MILLIS and $MAX_COOLDOWN_MILLIS milliseconds"
        }
    }

    companion object {
        const val MIN_COOLDOWN_MILLIS = 250
        const val MAX_COOLDOWN_MILLIS = 10_000
    }
}

sealed interface ServerHelloEnvelope {
    data class Valid(
        val hello: ServerHello,
    ) : ServerHelloEnvelope

    data object DuplicateEmotionIds : ServerHelloEnvelope
}

data class ClientHello(
    val capabilities: ProtocolCapabilities,
)
