package me.whish.emotify.client

import com.mojang.blaze3d.vertex.PoseStack
import me.whish.emotify.client.presentation.EmotionBillboardLayout
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

object EmotionBillboardRenderer {
    @JvmStatic
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
        if (minecraft.gameRenderer.useImprovedTransparency()) {
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
        val partialTick = minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)

        poseStack.pushPose()
        try {
            val renderOffset = renderer.getRenderOffset(renderState)
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                EmotionBillboardPlacement.localY(player, renderOffset.y, partialTick),
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.rotate(cameraRenderState.orientation)
            EmotionBillboardGeometry.submit(
                submitNodeCollector,
                poseStack,
                presentation.textureId,
                presentation.regionAt(elapsedMillis.toLong()),
                elapsedMillis,
                EmotifyClientConfig.animationMotion(),
                active.animationSeed,
            )
        } finally {
            poseStack.popPose()
        }
    }

    @JvmStatic
    fun shouldHideNameTag(renderState: AvatarRenderState): Boolean {
        val player = Minecraft.getInstance().level?.getEntity(renderState.id) as? AbstractClientPlayer ?: return false
        return ClientHandshakeController.shouldHideNameTagFor(player)
    }

    private const val MAX_DISTANCE_SQUARED = 64.0 * 64.0
}
