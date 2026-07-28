package me.whish.emotify.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import me.whish.emotify.domain.AnimationMotion
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.Pose
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.TriState

object EmotionBillboardRenderer {
    private val renderTypes = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered.map(EmotionPresentation::texture).distinct().associateWith { texture ->
            RenderType.entityTranslucent(texture, false)
        },
    )

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onRenderPlayer)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ::onRenderNameTag)
    }

    private fun onRenderPlayer(event: RenderPlayerEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = event.entity as? AbstractClientPlayer ?: return
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
        val opacity = EmotionAnimation.opacityByteAt(elapsedMillis)
        if (opacity == 0) {
            return
        }
        val presentation = EmotionPresentationCatalog.find(active.emotionId) ?: return
        val renderType = renderTypes[presentation.texture] ?: return
        val poseStack = event.poseStack
        poseStack.pushPose()
        try {
            val renderOffset = event.renderer.getRenderOffset(player, event.partialTick)
            val sourcePose = when {
                player.isFallFlying -> Pose.FALL_FLYING
                player.isSleeping -> Pose.SLEEPING
                player.isAutoSpinAttack -> Pose.SPIN_ATTACK
                else -> player.pose
            }
            val visualPose = EmotionBillboardLayout.visualPose(sourcePose)
            val visualHeight = player.getDimensions(visualPose).height().toDouble()
            val targetLocalY = EmotionBillboardLayout.localY(visualHeight, renderOffset.y, sourcePose)
            val localY = if (sourcePose == Pose.FALL_FLYING) {
                val uprightLocalY = EmotionBillboardLayout.localY(
                    player.getDimensions(Pose.STANDING).height().toDouble(),
                    renderOffset.y,
                    Pose.STANDING,
                )
                EmotionBillboardLayout.fallFlyingLocalY(
                    uprightLocalY,
                    targetLocalY,
                    player.fallFlyingTicks,
                    event.partialTick,
                )
            } else {
                targetLocalY
            }
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                localY + EmotionAnimation.verticalOffsetAt(elapsedMillis, AnimationMotion.FULL),
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.mulPose(minecraft.entityRenderDispatcher.cameraOrientation())
            val scale = (
                EmotionBillboardLayout.ICON_SIZE.toDouble() *
                    EmotionAnimation.scaleAt(elapsedMillis, AnimationMotion.FULL)
                ).toFloat()
            poseStack.scale(
                scale,
                scale,
                scale,
            )

            val pose = poseStack.last()
            val consumer = event.multiBufferSource.getBuffer(renderType)
            val region = presentation.region
            vertex(consumer, pose, -0.5f, -0.5f, region.u0, region.v1, opacity, event.packedLight)
            vertex(consumer, pose, 0.5f, -0.5f, region.u1, region.v1, opacity, event.packedLight)
            vertex(consumer, pose, 0.5f, 0.5f, region.u1, region.v0, opacity, event.packedLight)
            vertex(consumer, pose, -0.5f, 0.5f, region.u0, region.v0, opacity, event.packedLight)
        } finally {
            poseStack.popPose()
        }
    }

    private fun onRenderNameTag(event: RenderNameTagEvent) {
        val player = event.entity as? AbstractClientPlayer ?: return
        if (!ClientHandshakeController.shouldHideNameTagFor(player)) return
        event.setCanRender(TriState.FALSE)
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
