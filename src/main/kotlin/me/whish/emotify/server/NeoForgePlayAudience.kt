package me.whish.emotify.server

import me.whish.emotify.Emotify
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.network.payload.EmotionPlayPayload
import net.minecraft.server.level.ServerPlayer

object NeoForgePlayAudience {
    private val sendFailureDiagnostics = TokenBucket(
        capacity = SEND_FAILURE_LOG_CAPACITY,
        refillTokensPerSecond = SEND_FAILURE_LOG_REFILL,
        timeSource = SystemMonotonicTimeSource,
    )

    fun send(
        source: ServerPlayer,
        payload: EmotionPlayPayload,
        sessionResolver: (ServerPlayer) -> ServerPlayerSession?,
    ): Int {
        var delivered = 0
        val sourceSession = sessionResolver(source)
        if (sourceSession != null && admit(source, source, sourceSession, tracking = false, self = true)) {
            delivered += deliver(source, payload, sourceSession)
        }

        var candidateIndex = 0
        for (recipient in source.serverLevel().chunkSource.chunkMap.getPlayersWatching(source)) {
            if (!AudiencePolicy.canVisitCandidate(candidateIndex)) {
                break
            }
            candidateIndex += 1
            val recipientSession = sessionResolver(recipient) ?: continue
            if (!admit(source, recipient, recipientSession, tracking = true, self = false)) {
                continue
            }
            delivered += deliver(recipient, payload, recipientSession)
        }
        return delivered
    }

    private fun deliver(
        recipient: ServerPlayer,
        payload: EmotionPlayPayload,
        session: ServerPlayerSession,
    ): Int {
        return try {
            recipient.connection.send(payload)
            1
        } catch (exception: RuntimeException) {
            session.refundPlay()
            if (sendFailureDiagnostics.tryConsume()) {
                Emotify.LOGGER.warn(
                    "Failed to deliver Emotify play to player {}",
                    recipient.uuid,
                    exception,
                )
            }
            0
        }
    }

    private fun admit(
        source: ServerPlayer,
        recipient: ServerPlayer,
        recipientSession: ServerPlayerSession,
        tracking: Boolean,
        self: Boolean,
    ): Boolean {
        if (!recipient.connection.connection.isConnected || !recipient.connection.hasChannel(EmotionPlayPayload.TYPE)) {
            return false
        }
        val sameDimension = source.serverLevel() === recipient.serverLevel()
        val eligible = AudiencePolicy.isEligible(
            AudienceCandidate(
                tracking = tracking,
                negotiated = recipientSession.handshakeState is ServerHandshakeState.Supported,
                visible = self || !source.isInvisibleTo(recipient),
                sameDimension = sameDimension,
                distanceSquared = if (sameDimension) source.distanceToSqr(recipient) else Double.POSITIVE_INFINITY,
                self = self,
            ),
        )
        return eligible && recipientSession.tryAdmitPlay(self)
    }

    private const val SEND_FAILURE_LOG_CAPACITY = 4
    private const val SEND_FAILURE_LOG_REFILL = 1
}
