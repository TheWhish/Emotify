package me.whish.emotify.client

import me.whish.emotify.client.presentation.EmotionBillboardLayout
import me.whish.emotify.client.presentation.EmotionBillboardPose
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player

object EmotionBillboardPlacement {
    fun localY(player: Player, renderOffsetY: Double, partialTick: Float): Double {
        val sourcePose = sourcePose(player)
        val poseResolution = EmotionBillboardPoseResolver.resolve(sourcePose)
        val visualHeight = player.getDimensions(poseResolution.visualPose).height().toDouble()
        val targetLocalY = EmotionBillboardLayout.localY(
            visualHeight,
            renderOffsetY,
            poseResolution.layoutPose,
        )
        if (sourcePose != Pose.FALL_FLYING) {
            return targetLocalY
        }
        val uprightLocalY = EmotionBillboardLayout.localY(
            player.getDimensions(Pose.STANDING).height().toDouble(),
            renderOffsetY,
            EmotionBillboardPose.UPRIGHT,
        )
        return EmotionBillboardLayout.fallFlyingLocalY(
            uprightLocalY,
            targetLocalY,
            player.fallFlyingTicks,
            partialTick,
        )
    }

    private fun sourcePose(player: Player): Pose = when {
        player.isFallFlying -> Pose.FALL_FLYING
        player.isSleeping -> Pose.SLEEPING
        player.isAutoSpinAttack -> Pose.SPIN_ATTACK
        else -> player.pose
    }
}


