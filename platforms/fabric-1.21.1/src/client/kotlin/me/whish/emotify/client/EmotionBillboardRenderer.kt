package me.whish.emotify.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import me.whish.emotify.catalog.builtin.EmotionSpriteRegion
import me.whish.emotify.client.presentation.EmotionBillboardLayout
import me.whish.emotify.client.presentation.EmotionBillboardPose
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionAnimationFrameBuffer
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.Pose

object EmotionBillboardRenderer {
    private val animationFrame = EmotionAnimationFrameBuffer()
    private val renderTypes = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered.map(EmotionPresentation::textureId).distinct().associateWith { textureId ->
            RenderType.entityTranslucent(EmotionTextureResources.resolve(textureId), false)
        },
    )

    @JvmStatic
    fun render(
        player: AbstractClientPlayer,
        partialTick: Float,
        poseStack: PoseStack,
        multiBufferSource: MultiBufferSource,
        packedLight: Int,
        renderer: PlayerRenderer,
    ) {
        val minecraft = Minecraft.getInstance()
        val active = ClientHandshakeController.renderableEmotionFor(player) ?: return
        if (player === minecraft.cameraEntity && minecraft.options.cameraType.isFirstPerson) {
            return
        }

        val cameraPosition = minecraft.gameRenderer.mainCamera.position
        if (player.distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z) > MAX_DISTANCE_SQUARED) {
            return
        }

        val elapsedMillis = EmotionAnimation.elapsedMillis(
            active.startedAtNanos,
            SystemMonotonicTimeSource.nowNanos(),
        )
        EmotionAnimation.sampleInto(
            elapsedMillis,
            EmotifyClientConfig.animationMotion(),
            active.animationSeed,
            animationFrame,
        )
        if (!hasVisibleSprite()) {
            return
        }
        val presentation = EmotionPresentationCatalog.find(active.emotionId) ?: return
        val renderType = renderTypes[presentation.textureId] ?: return
        poseStack.pushPose()
        try {
            val renderOffset = renderer.getRenderOffset(player, partialTick)
            val sourcePose = when {
                player.isFallFlying -> Pose.FALL_FLYING
                player.isSleeping -> Pose.SLEEPING
                player.isAutoSpinAttack -> Pose.SPIN_ATTACK
                else -> player.pose
            }
            val poseResolution = EmotionBillboardPoseResolver.resolve(sourcePose)
            val visualHeight = player.getDimensions(poseResolution.visualPose).height().toDouble()
            val targetLocalY = EmotionBillboardLayout.localY(
                visualHeight,
                renderOffset.y,
                poseResolution.layoutPose,
            )
            val localY = if (sourcePose == Pose.FALL_FLYING) {
                val uprightLocalY = EmotionBillboardLayout.localY(
                    player.getDimensions(Pose.STANDING).height().toDouble(),
                    renderOffset.y,
                    EmotionBillboardPose.UPRIGHT,
                )
                EmotionBillboardLayout.fallFlyingLocalY(
                    uprightLocalY,
                    targetLocalY,
                    player.fallFlyingTicks,
                    partialTick,
                )
            } else {
                targetLocalY
            }
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                localY,
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.mulPose(minecraft.entityRenderDispatcher.cameraOrientation())
            val pose = poseStack.last()
            val consumer = multiBufferSource.getBuffer(renderType)
            val region = presentation.region
            var spriteIndex = animationFrame.spriteCount - 1
            while (spriteIndex >= 0) {
                renderSprite(
                    consumer,
                    pose,
                    region,
                    spriteIndex,
                    animationFrame.opacityByteAt(spriteIndex),
                    packedLight,
                )
                spriteIndex--
            }
        } finally {
            poseStack.popPose()
        }
    }

    @JvmStatic
    fun shouldHideNameTag(player: AbstractClientPlayer): Boolean =
        ClientHandshakeController.shouldHideNameTagFor(player)

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

    private fun renderSprite(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        region: EmotionSpriteRegion,
        spriteIndex: Int,
        opacity: Int,
        packedLight: Int,
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
        vertex(consumer, pose, centerX - halfWidth, centerY - halfHeight, region.u0, region.v1, opacity, packedLight)
        vertex(consumer, pose, centerX + halfWidth, centerY - halfHeight, region.u1, region.v1, opacity, packedLight)
        vertex(consumer, pose, centerX + halfWidth, centerY + halfHeight, region.u1, region.v0, opacity, packedLight)
        vertex(consumer, pose, centerX - halfWidth, centerY + halfHeight, region.u0, region.v0, opacity, packedLight)
    }

    private fun vertex(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        opacity: Int,
        packedLight: Int,
    ) {
        consumer.addVertex(pose, x, y, 0.0f)
            .setColor(255, 255, 255, opacity)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(packedLight)
            .setNormal(pose, 0.0f, 0.0f, 1.0f)
    }

    private const val MAX_DISTANCE_SQUARED = 64.0 * 64.0
}
