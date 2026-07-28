package me.whish.emotify.server

data class AudienceCandidate(
    val tracking: Boolean,
    val negotiated: Boolean,
    val visible: Boolean,
    val sameDimension: Boolean,
    val distanceSquared: Double,
    val self: Boolean = false,
)

object AudiencePolicy {
    const val RADIUS = 64.0
    const val MAX_DISTANCE_SQUARED = RADIUS * RADIUS
    const val MAX_TRACKING_CANDIDATES = 256

    fun isEligible(candidate: AudienceCandidate): Boolean =
        (candidate.self || candidate.tracking) &&
            candidate.negotiated &&
            candidate.visible &&
            candidate.sameDimension &&
            candidate.distanceSquared in 0.0..MAX_DISTANCE_SQUARED

    fun canVisitCandidate(candidateIndex: Int): Boolean {
        require(candidateIndex >= 0) { "Audience candidate index cannot be negative" }
        return candidateIndex < MAX_TRACKING_CANDIDATES
    }
}
