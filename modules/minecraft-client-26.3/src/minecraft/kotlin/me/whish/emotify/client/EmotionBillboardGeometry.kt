package me.whish.emotify.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionAnimationFrameBuffer
import net.minecraft.client.renderer.SubmitNodeCollector

object EmotionBillboardGeometry {
    private val animationFrame = EmotionAnimationFrameBuffer()

    fun submit(
        collector: SubmitNodeCollector,
        poseStack: PoseStack,
        textureId: String,
        region: EmotionSpriteRegion,
        elapsedMillis: Double,
        motion: AnimationMotion,
        animationSeed: Long,
    ) {
        collector.submitCustomGeometry(
            poseStack,
            EmotionBillboardRenderTypes.resolve(textureId),
        ) { pose, consumer ->
            EmotionAnimation.sampleInto(elapsedMillis, motion, animationSeed, animationFrame)
            renderSprites(consumer, pose, region)
        }
    }

    private fun renderSprites(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        region: EmotionSpriteRegion,
    ) {
        var spriteIndex = animationFrame.spriteCount - 1
        while (spriteIndex >= 0) {
            renderSprite(consumer, pose, region, spriteIndex, animationFrame.opacityByteAt(spriteIndex))
            spriteIndex--
        }
    }

    private fun renderSprite(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        region: EmotionSpriteRegion,
        spriteIndex: Int,
        opacity: Int,
    ) {
        if (opacity == 0) {
            return
        }
        val centerX = animationFrame.horizontalOffsetAt(spriteIndex).toFloat()
        val centerY = animationFrame.verticalOffsetAt(spriteIndex).toFloat()
        val halfWidth = (
            animationFrame.diameterAt(spriteIndex) * animationFrame.horizontalScaleAt(spriteIndex) * 0.5
            ).toFloat()
        val halfHeight = (
            animationFrame.diameterAt(spriteIndex) * animationFrame.verticalScaleAt(spriteIndex) * 0.5
            ).toFloat()
        vertex(consumer, pose, centerX - halfWidth, centerY - halfHeight, region.u0, region.v1, opacity)
        vertex(consumer, pose, centerX + halfWidth, centerY - halfHeight, region.u1, region.v1, opacity)
        vertex(consumer, pose, centerX + halfWidth, centerY + halfHeight, region.u1, region.v0, opacity)
        vertex(consumer, pose, centerX - halfWidth, centerY + halfHeight, region.u0, region.v0, opacity)
    }

    private fun vertex(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        opacity: Int,
    ) {
        consumer.addVertex(pose, x, y, 0.0f)
            .setColor(255, 255, 255, opacity)
            .setUv(u, v)
    }
}
