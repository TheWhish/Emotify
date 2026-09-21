package me.whish.emotify.client

import kotlin.math.roundToInt
import me.whish.emotify.client.presentation.EmotionHotbarFeedbackMath
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines

object EmotionHotbarFeedbackRenderer {
    fun render(guiGraphics: GuiGraphicsExtractor) {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        if (!EmotifyClientConfig.settings().showHotbarFeedback) {
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
        val centerX = EmotionHotbarFeedbackMath.centerX(minecraft.window.guiScaledWidth)
        val centerY = EmotionHotbarFeedbackMath.centerY(minecraft.window.guiScaledHeight, iconSize)

        val pose = guiGraphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(centerX, centerY + state.yOffset)
            pose.scale(state.scale, state.scale)
            pose.translate(-halfSize, -halfSize)

            val alphaByte = (state.alpha * 255.0f).roundToInt().coerceIn(0, 255)
            val color = (alphaByte shl 24) or 0x00FFFFFF
            guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                0,
                0,
                region.x.toFloat(),
                region.y.toFloat(),
                iconSize,
                iconSize,
                region.width,
                region.height,
                region.textureWidth,
                region.textureHeight,
                color,
            )
        } finally {
            pose.popMatrix()
        }
    }
}