package me.whish.emotify.server.core

import kotlin.time.Duration
import me.whish.emotify.domain.CooldownGate
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.MAX_SELECTION_RETRY_AFTER_MILLIS
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolFeatureRegistry
import me.whish.emotify.domain.ProtocolNegotiation
import me.whish.emotify.domain.ProtocolNegotiator
import me.whish.emotify.domain.SELECTION_REJECTION_BURST_CAPACITY
import me.whish.emotify.domain.SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.protocol.ClientHello

enum class ServerHandshakeFailure {
    INCOMPATIBLE_PROTOCOL,
    CHANGED_CLIENT_CAPABILITIES,
}

enum class ServerHandshakeTransition {
    SUPPORTED,
    UNSUPPORTED,
    NO_CHANGE,
}

sealed interface ServerHandshakeState {
    data object Pending : ServerHandshakeState

    data class Supported(
        val clientCapabilities: ProtocolCapabilities,
        val negotiated: ProtocolNegotiation.Supported,
    ) : ServerHandshakeState

    data class Unsupported(
        val reason: ServerHandshakeFailure,
        val initialClientCapabilities: ProtocolCapabilities,
    ) : ServerHandshakeState
}

sealed interface SelectionPreparation {
    data object Ready : SelectionPreparation

    data object Ignored : SelectionPreparation

    data class Rejected(
        val reason: SelectionRejectionReason,
        val retryAfterMillis: Int,
    ) : SelectionPreparation {
        init {
            require(retryAfterMillis in 0..MAX_SELECTION_RETRY_AFTER_MILLIS) {
                "Retry delay must be between 0 and $MAX_SELECTION_RETRY_AFTER_MILLIS ms: $retryAfterMillis"
            }
        }
    }
}

class ServerPlayerSession(
    private val serverCapabilities: ProtocolCapabilities,
    selectionCooldown: Duration,
    timeSource: MonotonicTimeSource,
    private val featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
) {
    private val selectionCooldown = CooldownGate(selectionCooldown, timeSource)
    private val rejectionResponses = TokenBucket(
        capacity = SELECTION_REJECTION_BURST_CAPACITY,
        refillTokensPerSecond = SELECTION_REJECTION_REFILL_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )
    private val playResponses = TokenBucket(
        capacity = PLAY_BURST_CAPACITY,
        refillTokensPerSecond = PLAY_REFILL_TOKENS_PER_SECOND,
        timeSource = timeSource,
    )

    var handshakeState: ServerHandshakeState = ServerHandshakeState.Pending
        private set

    fun receiveClientHello(hello: ClientHello): ServerHandshakeTransition =
        when (val current = handshakeState) {
            ServerHandshakeState.Pending -> establish(hello)
            is ServerHandshakeState.Supported -> verifyRepeat(current, hello)
            is ServerHandshakeState.Unsupported -> ServerHandshakeTransition.NO_CHANGE
        }

    fun prepareSelection(
        emotionId: EmotionId,
        policy: ServerSelectionPolicy,
        player: PlayerSnapshot,
    ): SelectionPreparation {
        if (handshakeState !is ServerHandshakeState.Supported) {
            return SelectionPreparation.Ignored
        }
        if (!policy.catalog.contains(emotionId)) {
            return SelectionPreparation.Ignored
        }
        if (!policy.enabled) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.SERVER_DISABLED, 0)
        }
        if (!policy.allowedEmotions.contains(emotionId)) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.EMOTION_DISABLED, 0)
        }
        if (!player.canPublish) {
            return SelectionPreparation.Rejected(SelectionRejectionReason.PLAYER_STATE, 0)
        }

        val remaining = selectionCooldown.remaining()
        if (remaining.isPositive()) {
            val remainingNanos = remaining.inWholeNanoseconds
            val retryAfterMillis = ((remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L)
                .coerceAtMost(MAX_SELECTION_RETRY_AFTER_MILLIS.toLong())
                .toInt()
            return SelectionPreparation.Rejected(SelectionRejectionReason.COOLDOWN, retryAfterMillis)
        }

        return SelectionPreparation.Ready
    }

    fun commitSelection() {
        check(selectionCooldown.tryAcquire()) { "Selection cooldown changed during main-thread validation" }
    }

    fun reconfigureSelectionCooldown(selectionCooldown: Duration) {
        this.selectionCooldown.reconfigure(selectionCooldown)
    }

    fun tryAdmitRejection(): Boolean = rejectionResponses.tryConsume()

    fun tryAdmitPlay(self: Boolean): Boolean =
        if (self) playResponses.tryConsume() else playResponses.tryConsumeRetaining(1)

    fun refundPlay() {
        playResponses.refundOne()
    }

    private fun establish(hello: ClientHello): ServerHandshakeTransition =
        when (val negotiated = ProtocolNegotiator.negotiate(serverCapabilities, hello.capabilities, featureRegistry)) {
            is ProtocolNegotiation.Supported -> {
                handshakeState = ServerHandshakeState.Supported(hello.capabilities, negotiated)
                ServerHandshakeTransition.SUPPORTED
            }
            is ProtocolNegotiation.Unsupported -> {
                handshakeState = ServerHandshakeState.Unsupported(
                    ServerHandshakeFailure.INCOMPATIBLE_PROTOCOL,
                    hello.capabilities,
                )
                ServerHandshakeTransition.UNSUPPORTED
            }
        }

    private fun verifyRepeat(
        current: ServerHandshakeState.Supported,
        hello: ClientHello,
    ): ServerHandshakeTransition {
        if (current.clientCapabilities == hello.capabilities) {
            return ServerHandshakeTransition.NO_CHANGE
        }

        handshakeState = ServerHandshakeState.Unsupported(
            ServerHandshakeFailure.CHANGED_CLIENT_CAPABILITIES,
            current.clientCapabilities,
        )
        return ServerHandshakeTransition.UNSUPPORTED
    }

    companion object {
        private const val PLAY_BURST_CAPACITY = 32
        private const val PLAY_REFILL_TOKENS_PER_SECOND = 16
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
