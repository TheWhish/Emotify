package me.whish.emotify.client.interaction

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class InteractionVector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Interaction vector must be finite" }
    }
}

data class EmotionInteractionRay(
    val origin: InteractionVector3,
    val direction: InteractionVector3,
)

data class EmotionBillboardHitArea(
    val center: InteractionVector3,
    val right: InteractionVector3,
    val up: InteractionVector3,
    val halfWidth: Double,
    val halfHeight: Double,
    val padding: Double = EmotionBillboardHitDetector.HIT_PADDING_BLOCKS,
) {
    init {
        require(halfWidth > 0.0 && halfWidth.isFinite()) { "Billboard half-width must be positive and finite" }
        require(halfHeight > 0.0 && halfHeight.isFinite()) { "Billboard half-height must be positive and finite" }
        require(padding >= 0.0 && padding.isFinite()) { "Billboard hit padding must be non-negative and finite" }
    }
}

object CustomEmotionCopyHitArea {
    const val WIDTH_BLOCKS = 0.9
    private const val TOP_OFFSET_BLOCKS = 0.85
    private const val BOTTOM_OFFSET_BLOCKS = -0.10
    const val HEIGHT_BLOCKS = TOP_OFFSET_BLOCKS - BOTTOM_OFFSET_BLOCKS

    fun create(
        anchor: InteractionVector3,
        right: InteractionVector3,
        up: InteractionVector3,
    ): EmotionBillboardHitArea {
        val upLength = sqrt(up.x * up.x + up.y * up.y + up.z * up.z)
        require(upLength > VECTOR_EPSILON) { "Custom emotion copy up axis must have a positive length" }
        val center = InteractionVector3(
            anchor.x + up.x / upLength * CENTER_OFFSET_BLOCKS,
            anchor.y + up.y / upLength * CENTER_OFFSET_BLOCKS,
            anchor.z + up.z / upLength * CENTER_OFFSET_BLOCKS,
        )
        return EmotionBillboardHitArea(
            center = center,
            right = right,
            up = up,
            halfWidth = WIDTH_BLOCKS * 0.5,
            halfHeight = HEIGHT_BLOCKS * 0.5,
            padding = 0.0,
        )
    }

    private const val CENTER_OFFSET_BLOCKS = (TOP_OFFSET_BLOCKS + BOTTOM_OFFSET_BLOCKS) * 0.5
    private const val VECTOR_EPSILON = 1.0e-9
}

object EmotionBillboardHitDetector {
    const val HIT_PADDING_BLOCKS = 0.04

    fun intersectionDistance(
        ray: EmotionInteractionRay,
        area: EmotionBillboardHitArea,
        maximumDistance: Double,
        occlusionDistance: Double,
    ): Double? {
        require(maximumDistance > 0.0 && maximumDistance.isFinite()) {
            "Maximum interaction distance must be positive and finite"
        }
        require(occlusionDistance >= 0.0 && occlusionDistance.isFinite()) {
            "Occlusion distance must be non-negative and finite"
        }

        val directionLength = length(ray.direction)
        val rightLength = length(area.right)
        val upLength = length(area.up)
        if (directionLength <= VECTOR_EPSILON || rightLength <= VECTOR_EPSILON || upLength <= VECTOR_EPSILON) {
            return null
        }

        val directionX = ray.direction.x / directionLength
        val directionY = ray.direction.y / directionLength
        val directionZ = ray.direction.z / directionLength
        val normalX = area.right.y * area.up.z - area.right.z * area.up.y
        val normalY = area.right.z * area.up.x - area.right.x * area.up.z
        val normalZ = area.right.x * area.up.y - area.right.y * area.up.x
        val normalLength = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (normalLength <= VECTOR_EPSILON) {
            return null
        }

        val normalizedNormalX = normalX / normalLength
        val normalizedNormalY = normalY / normalLength
        val normalizedNormalZ = normalZ / normalLength
        val denominator =
            directionX * normalizedNormalX +
                directionY * normalizedNormalY +
                directionZ * normalizedNormalZ
        if (abs(denominator) <= PARALLEL_EPSILON) {
            return null
        }

        val centerOffsetX = area.center.x - ray.origin.x
        val centerOffsetY = area.center.y - ray.origin.y
        val centerOffsetZ = area.center.z - ray.origin.z
        val distance = (
            centerOffsetX * normalizedNormalX +
                centerOffsetY * normalizedNormalY +
                centerOffsetZ * normalizedNormalZ
            ) / denominator
        if (distance < 0.0 || distance > min(maximumDistance, occlusionDistance) + DISTANCE_EPSILON) {
            return null
        }

        val intersectionOffsetX = ray.origin.x + directionX * distance - area.center.x
        val intersectionOffsetY = ray.origin.y + directionY * distance - area.center.y
        val intersectionOffsetZ = ray.origin.z + directionZ * distance - area.center.z
        val horizontal = (
            intersectionOffsetX * area.right.x +
                intersectionOffsetY * area.right.y +
                intersectionOffsetZ * area.right.z
            ) / rightLength
        val vertical = (
            intersectionOffsetX * area.up.x +
                intersectionOffsetY * area.up.y +
                intersectionOffsetZ * area.up.z
            ) / upLength
        if (abs(horizontal) > area.halfWidth + area.padding || abs(vertical) > area.halfHeight + area.padding) {
            return null
        }
        return distance
    }

    private fun length(vector: InteractionVector3): Double =
        sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z)

    private const val VECTOR_EPSILON = 1.0e-9
    private const val PARALLEL_EPSILON = 1.0e-8
    private const val DISTANCE_EPSILON = 1.0e-7
}
