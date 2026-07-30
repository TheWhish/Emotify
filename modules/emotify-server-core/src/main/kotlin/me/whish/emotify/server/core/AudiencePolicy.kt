package me.whish.emotify.server.core

import java.util.UUID

data class ServerAudiencePolicy(
    val radius: Double,
    val maximumTrackingCandidates: Int,
) {
    val maximumDistanceSquared: Double = radius * radius

    init {
        require(radius.isFinite() && radius > 0.0 && radius <= AudiencePolicy.RADIUS) {
            "Audience radius must be finite and between 0 and ${AudiencePolicy.RADIUS}: $radius"
        }
        require(maximumTrackingCandidates in 1..AudiencePolicy.MAX_TRACKING_CANDIDATES) {
            "Maximum tracking candidates must be between 1 and ${AudiencePolicy.MAX_TRACKING_CANDIDATES}: " +
                maximumTrackingCandidates
        }
    }

    companion object {
        val DEFAULT = ServerAudiencePolicy(AudiencePolicy.RADIUS, AudiencePolicy.MAX_TRACKING_CANDIDATES)
    }
}

object AudiencePolicy {
    const val RADIUS = 64.0
    const val MAX_DISTANCE_SQUARED = RADIUS * RADIUS
    const val MAX_TRACKING_CANDIDATES = 256

    fun isEligible(
        tracking: Boolean,
        negotiated: Boolean,
        visible: Boolean,
        sameDimension: Boolean,
        distanceSquared: Double,
        self: Boolean = false,
    ): Boolean = isEligible(
        ServerAudiencePolicy.DEFAULT,
        tracking,
        negotiated,
        visible,
        sameDimension,
        distanceSquared,
        self,
    )

    fun isEligible(
        policy: ServerAudiencePolicy,
        tracking: Boolean,
        negotiated: Boolean,
        visible: Boolean,
        sameDimension: Boolean,
        distanceSquared: Double,
        self: Boolean = false,
    ): Boolean =
        (self || tracking) &&
            negotiated &&
            visible &&
            sameDimension &&
            distanceSquared >= 0.0 && distanceSquared <= policy.maximumDistanceSquared
}

enum class AudienceVisitCompletion {
    EXHAUSTED,
    LIMIT_REACHED,
}

fun interface AudienceVisitor {
    fun visit(
        playerId: UUID,
        connectionId: ConnectionId,
        visible: Boolean,
        sameDimension: Boolean,
        distanceSquared: Double,
    ): Boolean
}

fun interface AudiencePort {
    fun visitTracking(
        source: PlayerSnapshot,
        maxCandidates: Int,
        visitor: AudienceVisitor,
    ): AudienceVisitCompletion
}
