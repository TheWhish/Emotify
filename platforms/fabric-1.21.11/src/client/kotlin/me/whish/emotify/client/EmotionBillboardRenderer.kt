package me.whish.emotify.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.presentation.EmotionBillboardLayout
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionAnimationFrameBuffer
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.CameraRenderState

object EmotionBillboardRenderer {
    private val animationFrame = EmotionAnimationFrameBuffer()

    @JvmStatic
    fun flushAfterWeather() {
        EmotionBillboardDeferredBuffer.flush()
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun render(
        renderState: AvatarRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
        renderer: AvatarRenderer<*>,
    ) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.level?.getEntity(renderState.id) as? AbstractClientPlayer ?: return
        val active = ClientHandshakeController.renderableEmotionFor(player) ?: return
        if (player === minecraft.cameraEntity && minecraft.options.cameraType.isFirstPerson) {
            return
        }

        val cameraPosition = cameraRenderState.pos
        if (player.distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z) > MAX_DISTANCE_SQUARED) {
            return
        }

        val presentation = EmotionPresentationRegistry.find(active.emotionId) ?: return
        val elapsedMillis = EmotionAnimation.elapsedMillis(
            active.startedAtNanos,
            SystemMonotonicTimeSource.nowNanos(),
        )
        val motion = EmotifyClientConfig.animationMotion()
        val animationSeed = active.animationSeed
        val partialTick = minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)
        val pass = if (Minecraft.useShaderTransparency()) {
            EmotionBillboardRenderPass.COMPOSITED
        } else {
            EmotionBillboardRenderPass.FINAL_DIRECT
        }

        poseStack.pushPose()
        try {
            val renderOffset = renderer.getRenderOffset(renderState)
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                EmotionBillboardPlacement.localY(player, renderOffset.y, partialTick),
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.mulPose(cameraRenderState.orientation)
            EmotionAnimation.sampleInto(
                elapsedMillis,
                motion,
                animationSeed,
                animationFrame,
            )
            if (hasVisibleSprite()) {
                renderSprites(
                    EmotionBillboardDeferredBuffer.consumer(presentation.textureId, pass),
                    poseStack.last(),
                    presentation.regionAt(elapsedMillis.toLong()),
                )
            }
        } finally {
            poseStack.popPose()
        }
    }

    @JvmStatic
    fun shouldHideNameTag(renderState: AvatarRenderState): Boolean {
        val player = Minecraft.getInstance().level?.getEntity(renderState.id) as? AbstractClientPlayer ?: return false
        return ClientHandshakeController.shouldHideNameTagFor(player)
    }

    private fun hasVisibleSprite(): Boolean {
        var spriteIndex = 0
        while (spriteIndex < animationFrame.spriteCount) {
            if (animationFrame.opacityByteAt(spriteIndex) > 0) {
                return true
            }
            spriteIndex++
        }
        return false
    }

    private fun renderSprites(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        region: EmotionSpriteRegion,
    ) {
        var spriteIndex = animationFrame.spriteCount - 1
        while (spriteIndex >= 0) {
            renderSprite(
                consumer,
                pose,
                region,
                spriteIndex,
                animationFrame.opacityByteAt(spriteIndex),
            )
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
            animationFrame.diameterAt(spriteIndex) *
                animationFrame.horizontalScaleAt(spriteIndex) *
                0.5
            ).toFloat()
        val halfHeight = (
            animationFrame.diameterAt(spriteIndex) *
                animationFrame.verticalScaleAt(spriteIndex) *
                0.5
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

    private const val MAX_DISTANCE_SQUARED = 64.0 * 64.0
}
