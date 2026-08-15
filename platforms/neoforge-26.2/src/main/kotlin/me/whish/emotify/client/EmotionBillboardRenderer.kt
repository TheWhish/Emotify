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
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.minecraft.util.TriState

object EmotionBillboardRenderer {
    private val animationFrame = EmotionAnimationFrameBuffer()

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onRenderPlayer)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ::onRenderNameTag)
        NeoForge.EVENT_BUS.addListener(::onAfterWeather)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAfterWeather(event: RenderLevelStageEvent.AfterWeather) {
        EmotionBillboardDeferredBuffer.flush()
    }

    private fun onRenderPlayer(event: RenderPlayerEvent.Post<*>) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.level?.getEntity(event.renderState.id) as? AbstractClientPlayer ?: return
        val active = ClientHandshakeController.renderableEmotionFor(player) ?: return
        if (player === minecraft.cameraEntity && minecraft.options.cameraType.isFirstPerson) {
            return
        }

        val cameraPosition = minecraft.gameRenderer.mainCamera().position()
        if (player.distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z) > MAX_DISTANCE_SQUARED) {
            return
        }

        val elapsedMillis = EmotionAnimation.elapsedMillis(
            active.startedAtNanos,
            SystemMonotonicTimeSource.nowNanos(),
        )
        val presentation = EmotionPresentationRegistry.find(active.emotionId) ?: return
        val motion = EmotifyClientConfig.animationMotion()
        val animationSeed = active.animationSeed
        val region = presentation.regionAt(elapsedMillis.toLong())
        val poseStack = event.poseStack
        poseStack.pushPose()
        try {
            val renderOffset = event.renderer.getRenderOffset(event.renderState)
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                EmotionBillboardPlacement.localY(player, renderOffset.y, event.partialTick),
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.mulPose(minecraft.gameRenderer.mainCamera().rotation())
            EmotionAnimation.sampleInto(
                elapsedMillis,
                motion,
                animationSeed,
                animationFrame,
            )
            if (hasVisibleSprite()) {
                renderSprites(
                    EmotionBillboardDeferredBuffer.consumer(presentation.textureId),
                    poseStack.last(),
                    region,
                )
            }
        } finally {
            poseStack.popPose()
        }
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

    private fun onRenderNameTag(event: RenderNameTagEvent.CanRender) {
        val player = event.entity as? AbstractClientPlayer ?: return
        if (!ClientHandshakeController.shouldHideNameTagFor(player)) return
        event.setCanRender(TriState.FALSE)
    }

    private const val MAX_DISTANCE_SQUARED = 64.0 * 64.0
}
