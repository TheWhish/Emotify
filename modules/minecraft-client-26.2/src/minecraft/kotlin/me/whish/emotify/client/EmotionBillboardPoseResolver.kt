package me.whish.emotify.client

import me.whish.emotify.client.presentation.EmotionBillboardPose
import net.minecraft.world.entity.Pose

data class EmotionBillboardPoseResolution(
    val layoutPose: EmotionBillboardPose,
    val visualPose: Pose,
)

object EmotionBillboardPoseResolver {
    fun resolve(sourcePose: Pose): EmotionBillboardPoseResolution = when (sourcePose) {
        Pose.SLEEPING -> sleeping
        Pose.FALL_FLYING -> fallFlying
        Pose.SWIMMING -> swimming
        Pose.SPIN_ATTACK -> spinAttack
        else -> upright
    }

    private val upright = EmotionBillboardPoseResolution(EmotionBillboardPose.UPRIGHT, Pose.STANDING)
    private val sleeping = EmotionBillboardPoseResolution(EmotionBillboardPose.SLEEPING, Pose.SLEEPING)
    private val fallFlying = EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.FALL_FLYING)
    private val swimming = EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.SWIMMING)
    private val spinAttack = EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.SPIN_ATTACK)
}


