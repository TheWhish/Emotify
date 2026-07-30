package me.whish.emotify.client

import me.whish.emotify.domain.EmotionAnimation
import net.minecraft.world.entity.Pose

object EmotionBillboardLayout {
    fun localX(renderOffsetX: Double): Double = -renderOffsetX

    fun localY(visualHeight: Double, renderOffsetY: Double, pose: Pose): Double =
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

    fun visualPose(pose: Pose): Pose = when (pose) {
        Pose.FALL_FLYING,
        Pose.SLEEPING,
        Pose.SWIMMING,
        Pose.SPIN_ATTACK,
        -> pose
        else -> Pose.STANDING
    }

    fun bottomClearance(pose: Pose): Double = centerOffset(pose) - CLUSTER_BOTTOM_EXTENT

    private fun centerOffset(pose: Pose): Double = when (visualPose(pose)) {
        Pose.SLEEPING -> SLEEPING_CENTER_OFFSET
        Pose.FALL_FLYING,
        Pose.SWIMMING,
        Pose.SPIN_ATTACK,
        -> PRONE_CENTER_OFFSET
        else -> UPRIGHT_CENTER_OFFSET
    }

    private const val CLUSTER_BOTTOM_EXTENT = EmotionAnimation.MAX_BOTTOM_EXTENT_BLOCKS
    private const val UPRIGHT_CENTER_OFFSET = 0.27
    private const val PRONE_CENTER_OFFSET = 0.47
    private const val SLEEPING_CENTER_OFFSET = 0.67
    private const val FALL_FLYING_TRANSITION_TICKS_SQUARED = 100.0
}
