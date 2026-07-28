package me.whish.emotify.client

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.domain.ProtocolNegotiation
import me.whish.emotify.domain.ProtocolNegotiator
import me.whish.emotify.protocol.ServerHello

data class ClientPolicy(
    val cooldownMillis: Int,
    val allowedEmotions: EmotionCatalog,
)

enum class ClientHandshakeFailure {
    TIMEOUT,
    INCOMPATIBLE_PROTOCOL,
    CHANGED_SERVER_CAPABILITIES,
    DUPLICATE_SERVER_CATALOG,
}

enum class ClientHandshakeTransition {
    SUPPORTED,
    POLICY_UPDATED,
    UNSUPPORTED,
    NO_CHANGE,
    IGNORED,
}

sealed interface ClientHandshakeState {
    data object Disconnected : ClientHandshakeState

    data class Pending(
        val connectionId: Long,
        val startedAtNanos: Long,
    ) : ClientHandshakeState

    data class Supported(
        val connectionId: Long,
        val negotiated: ProtocolNegotiation.Supported,
        val serverCapabilities: ProtocolCapabilities,
        val policy: ClientPolicy,
    ) : ClientHandshakeState

    data class Unsupported(
        val connectionId: Long,
        val reason: ClientHandshakeFailure,
    ) : ClientHandshakeState
}

class ClientHandshakeSession(
    private val localCapabilities: ProtocolCapabilities,
    private val localCatalog: EmotionCatalog,
    private val timeSource: MonotonicTimeSource,
    private val featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
) {
    var state: ClientHandshakeState = ClientHandshakeState.Disconnected
        private set

    fun begin(connectionId: Long) {
        state = ClientHandshakeState.Pending(connectionId, timeSource.nowNanos())
    }

    fun pollTimeout(): ClientHandshakeTransition {
        val current = state as? ClientHandshakeState.Pending ?: return ClientHandshakeTransition.NO_CHANGE
        val elapsedNanos = timeSource.nowNanos() - current.startedAtNanos
        check(elapsedNanos >= 0L) { "Monotonic time source moved backwards" }
        if (elapsedNanos < HANDSHAKE_TIMEOUT_NANOS) {
            return ClientHandshakeTransition.NO_CHANGE
        }

        state = ClientHandshakeState.Unsupported(current.connectionId, ClientHandshakeFailure.TIMEOUT)
        return ClientHandshakeTransition.UNSUPPORTED
    }

    fun receiveServerHello(connectionId: Long, hello: ServerHello): ClientHandshakeTransition {
        val current = state
        if (current.connectionIdOrNull() != connectionId) {
            return ClientHandshakeTransition.IGNORED
        }

        return when (current) {
            is ClientHandshakeState.Pending -> establish(connectionId, hello)
            is ClientHandshakeState.Supported -> refresh(current, hello)
            is ClientHandshakeState.Unsupported -> {
                if (current.reason == ClientHandshakeFailure.TIMEOUT) {
                    establish(connectionId, hello)
                } else {
                    ClientHandshakeTransition.NO_CHANGE
                }
            }
            ClientHandshakeState.Disconnected -> ClientHandshakeTransition.IGNORED
        }
    }

    fun rejectDuplicateServerCatalog(connectionId: Long): ClientHandshakeTransition {
        if (state.connectionIdOrNull() != connectionId) {
            return ClientHandshakeTransition.IGNORED
        }
        val current = state
        if (current is ClientHandshakeState.Unsupported && current.reason != ClientHandshakeFailure.TIMEOUT) {
            return ClientHandshakeTransition.NO_CHANGE
        }

        state = ClientHandshakeState.Unsupported(connectionId, ClientHandshakeFailure.DUPLICATE_SERVER_CATALOG)
        return ClientHandshakeTransition.UNSUPPORTED
    }

    fun disconnect(connectionId: Long) {
        if (state.connectionIdOrNull() == connectionId) {
            state = ClientHandshakeState.Disconnected
        }
    }

    private fun establish(connectionId: Long, hello: ServerHello): ClientHandshakeTransition {
        return when (val negotiated = ProtocolNegotiator.negotiate(localCapabilities, hello.capabilities, featureRegistry)) {
            is ProtocolNegotiation.Supported -> {
                state = ClientHandshakeState.Supported(
                    connectionId,
                    negotiated,
                    hello.capabilities,
                    policyFrom(hello),
                )
                ClientHandshakeTransition.SUPPORTED
            }
            is ProtocolNegotiation.Unsupported -> {
                state = ClientHandshakeState.Unsupported(connectionId, ClientHandshakeFailure.INCOMPATIBLE_PROTOCOL)
                ClientHandshakeTransition.UNSUPPORTED
            }
        }
    }

    private fun refresh(
        current: ClientHandshakeState.Supported,
        hello: ServerHello,
    ): ClientHandshakeTransition {
        if (current.serverCapabilities != hello.capabilities) {
            state = ClientHandshakeState.Unsupported(
                current.connectionId,
                ClientHandshakeFailure.CHANGED_SERVER_CAPABILITIES,
            )
            return ClientHandshakeTransition.UNSUPPORTED
        }

        val refreshedPolicy = policyFrom(hello)
        if (current.policy == refreshedPolicy) {
            return ClientHandshakeTransition.NO_CHANGE
        }

        state = current.copy(policy = refreshedPolicy)
        return ClientHandshakeTransition.POLICY_UPDATED
    }

    private fun policyFrom(hello: ServerHello): ClientPolicy {
        val allowedEmotions = localCatalog.ids.filter(hello.emotionCatalog::contains)
        return ClientPolicy(hello.cooldownMillis, EmotionCatalog.of(allowedEmotions))
    }

    private fun ClientHandshakeState.connectionIdOrNull(): Long? = when (this) {
        is ClientHandshakeState.Pending -> connectionId
        is ClientHandshakeState.Supported -> connectionId
        is ClientHandshakeState.Unsupported -> connectionId
        ClientHandshakeState.Disconnected -> null
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_NANOS = 5_000_000_000L
    }
}
