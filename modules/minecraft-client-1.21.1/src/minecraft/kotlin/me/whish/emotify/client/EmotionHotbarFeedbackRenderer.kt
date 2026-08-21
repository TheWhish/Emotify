package me.whish.emotify.client

import com.mojang.blaze3d.systems.RenderSystem
import me.whish.emotify.client.presentation.EmotionHotbarFeedbackMath
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

object EmotionHotbarFeedbackRenderer {
    fun render(guiGraphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        if (minecraft.options.hideGui || !EmotifyClientConfig.settings().showHotbarFeedback) {
            return
        }

        val active = ClientHandshakeController.renderableEmotionFor(localPlayer) ?: return
        val elapsedMillis = EmotionAnimation.elapsedMillis(
            active.startedAtNanos,
            SystemMonotonicTimeSource.nowNanos(),
        )
        val state = EmotionHotbarFeedbackMath.evaluate(elapsedMillis)
        if (!state.isVisible || state.alpha <= 0.0f) {
            return
        }

        val presentation = EmotionPresentationRegistry.find(active.emotionId) ?: return
        val texture = EmotionTextureResources.resolve(presentation.textureId)
        val region = presentation.regionAt(elapsedMillis.toLong())
        val iconSize = EmotionHotbarFeedbackMath.DEFAULT_ICON_SIZE
        val halfSize = iconSize * 0.5f
        val centerX = EmotionHotbarFeedbackMath.centerX(guiGraphics.guiWidth())
        val centerY = EmotionHotbarFeedbackMath.centerY(guiGraphics.guiHeight(), iconSize)

        val poseStack = guiGraphics.pose()
        poseStack.pushPose()
        try {
            poseStack.translate(centerX, centerY + state.yOffset, 0.0f)
            poseStack.scale(state.scale, state.scale, 1.0f)
            poseStack.translate(-halfSize, -halfSize, 0.0f)

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()

            guiGraphics.setColor(1.0f, 1.0f, 1.0f, state.alpha)
            guiGraphics.blit(
                texture,
                0,
                0,
                iconSize,
                iconSize,
                region.x.toFloat(),
                region.y.toFloat(),
                region.width,
                region.height,
                region.textureWidth,
                region.textureHeight,
            )
            guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
        } finally {
            poseStack.popPose()
        }
    }
}