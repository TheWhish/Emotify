package me.whish.emotify.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import java.util.LinkedHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.RenderType

object EmotionBillboardDeferredBuffer {
    private val stagedBuffer = StagedVertexBuffer({ "Emotify emotion billboards" }, BUFFER_SIZE)
    private val pendingByTextureId = LinkedHashMap<String, PendingDraw>()

    fun consumer(textureId: String): VertexConsumer {
        val pending = pendingByTextureId.getOrPut(textureId) {
            val composited = EmotionBillboardRenderTypes.resolve(textureId, EmotionBillboardRenderPass.COMPOSITED)
            val finalDirect = EmotionBillboardRenderTypes.resolve(textureId, EmotionBillboardRenderPass.FINAL_DIRECT)
            PendingDraw(
                composited,
                finalDirect,
                stagedBuffer.appendDraw(
                    finalDirect.format(),
                    finalDirect.primitiveTopology(),
                    RenderSystem.getProjectionType().vertexSorting(),
                ),
            )
        }
        return stagedBuffer.getVertexBuilder(pending.draw)
    }

    fun flush() {
        if (pendingByTextureId.isEmpty()) {
            return
        }
        try {
            stagedBuffer.upload()
            val composited = Minecraft.getInstance().levelRenderer.weatherTarget() != null
            pendingByTextureId.values.forEach { pending ->
                val executeInfo = stagedBuffer.getExecuteInfo(pending.draw) ?: return@forEach
                val renderType = if (composited) pending.composited else pending.finalDirect
                renderType.prepare().drawFromBuffer(executeInfo)
            }
        } finally {
            pendingByTextureId.clear()
            stagedBuffer.endFrame()
        }
    }

    private data class PendingDraw(
        val composited: RenderType,
        val finalDirect: RenderType,
        val draw: StagedVertexBuffer.Draw,
    )

    private const val BUFFER_SIZE = 1_536
}
