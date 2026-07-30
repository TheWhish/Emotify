package me.whish.emotify.client.presentation

import me.whish.emotify.domain.EmotionAnimation

enum class EmotionBillboardPose {
    UPRIGHT,
    PRONE,
    SLEEPING,
}

object EmotionBillboardLayout {
    fun localX(renderOffsetX: Double): Double = -renderOffsetX

    fun localY(visualHeight: Double, renderOffsetY: Double, pose: EmotionBillboardPose): Double =
        visualHeight + centerOffset(pose) - renderOffsetY

    fun localZ(renderOffsetZ: Double): Double = -renderOffsetZ

    fun fallFlyingLocalY(
        uprightLocalY: Double,
        proneLocalY: Double,
        fallFlyingTicks: Int,
        partialTick: Float,
    ): Double {
        val elapsedTicks = fallFlyingTicks.coerceAtLeast(0).toDouble() +
            partialTick.toDouble().coerceIn(0.0, 1.0)
        val transition = (elapsedTicks * elapsedTicks / FALL_FLYING_TRANSITION_TICKS_SQUARED)
            .coerceIn(0.0, 1.0)
        return uprightLocalY + (proneLocalY - uprightLocalY) * transition
    }

    fun bottomClearance(pose: EmotionBillboardPose): Double = centerOffset(pose) - CLUSTER_BOTTOM_EXTENT

    private fun centerOffset(pose: EmotionBillboardPose): Double = when (pose) {
        EmotionBillboardPose.UPRIGHT -> UPRIGHT_CENTER_OFFSET
        EmotionBillboardPose.PRONE -> PRONE_CENTER_OFFSET
        EmotionBillboardPose.SLEEPING -> SLEEPING_CENTER_OFFSET
    }

    private const val CLUSTER_BOTTOM_EXTENT = EmotionAnimation.MAX_BOTTOM_EXTENT_BLOCKS
    private const val UPRIGHT_CENTER_OFFSET = 0.27
    private const val PRONE_CENTER_OFFSET = 0.47
    private const val SLEEPING_CENTER_OFFSET = 0.67
    private const val FALL_FLYING_TRANSITION_TICKS_SQUARED = 100.0
}
