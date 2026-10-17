package me.whish.emotify.client

import me.whish.emotify.client.presentation.EmotionBillboardLayout
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.util.TriState
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.common.NeoForge

object EmotionBillboardRenderer {
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onRenderPlayer)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ::onRenderNameTag)
    }

    private fun onRenderPlayer(event: RenderPlayerEvent.Post<*>) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.level?.getEntity(event.renderState.id) as? AbstractClientPlayer ?: return
        val active = ClientHandshakeController.renderableEmotionFor(player) ?: return
        if (player === minecraft.cameraEntity && minecraft.options.cameraType.isFirstPerson) {
            return
        }

        val camera = minecraft.gameRenderer.mainCamera()
        val cameraPosition = camera.position()
        if (player.distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z) > MAX_DISTANCE_SQUARED) {
            return
        }

        val presentation = EmotionPresentationRegistry.find(active.emotionId) ?: return
        val elapsedMillis = EmotionAnimation.elapsedMillis(
            active.startedAtNanos,
            SystemMonotonicTimeSource.nowNanos(),
        )
        val poseStack = event.poseStack
        poseStack.pushPose()
        try {
            val renderOffset = event.renderer.getRenderOffset(event.renderState)
            poseStack.translate(
                EmotionBillboardLayout.localX(renderOffset.x),
                EmotionBillboardPlacement.localY(player, renderOffset.y, event.partialTick),
                EmotionBillboardLayout.localZ(renderOffset.z),
            )
            poseStack.rotate(camera.rotation())
            EmotionBillboardGeometry.submit(
                event.submitNodeCollector,
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

    private fun onRenderNameTag(event: RenderNameTagEvent.CanRender) {
        val player = event.entity as? AbstractClientPlayer ?: return
        if (!ClientHandshakeController.shouldHideNameTagFor(player)) return
        event.setCanRender(TriState.FALSE)
    }

    private const val MAX_DISTANCE_SQUARED = 64.0 * 64.0
}
