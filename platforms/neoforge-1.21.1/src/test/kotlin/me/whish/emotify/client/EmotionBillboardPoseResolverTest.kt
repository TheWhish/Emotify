package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.presentation.EmotionBillboardPose
import net.minecraft.world.entity.Pose

@Suppress("unused")
class EmotionBillboardPoseResolverTest : FunSpec({
    test("default Minecraft poses resolve to the upright standing layout") {
        val specialPoses = setOf(Pose.FALL_FLYING, Pose.SLEEPING, Pose.SWIMMING, Pose.SPIN_ATTACK)

        Pose.entries.filterNot(specialPoses::contains).forEach { sourcePose ->
            EmotionBillboardPoseResolver.resolve(sourcePose) shouldBe
                EmotionBillboardPoseResolution(EmotionBillboardPose.UPRIGHT, Pose.STANDING)
        }
    }

    test("prone and sleeping Minecraft poses preserve their visual dimensions") {
        mapOf(
            Pose.FALL_FLYING to EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.FALL_FLYING),
            Pose.SWIMMING to EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.SWIMMING),
            Pose.SPIN_ATTACK to EmotionBillboardPoseResolution(EmotionBillboardPose.PRONE, Pose.SPIN_ATTACK),
            Pose.SLEEPING to EmotionBillboardPoseResolution(EmotionBillboardPose.SLEEPING, Pose.SLEEPING),
        ).forEach { (sourcePose, expected) ->
            EmotionBillboardPoseResolver.resolve(sourcePose) shouldBe expected
        }
    }

    test("repeated pose resolution reuses cached instances") {
        Pose.entries.forEach { sourcePose ->
            val first = EmotionBillboardPoseResolver.resolve(sourcePose)
            val second = EmotionBillboardPoseResolver.resolve(sourcePose)

            (first === second) shouldBe true
        }
    }
})
