package me.whish.emotify.client

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexConsumer
import java.util.LinkedHashMap
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderType

object EmotionBillboardDeferredBuffer {
    private val fixedBuffers = LinkedHashMap<RenderType, ByteBufferBuilder>().apply {
        EmotionPresentationCatalog.ordered
            .map(EmotionPresentation::textureId)
            .distinct()
            .forEach { textureId ->
                EmotionBillboardRenderPass.entries.forEach { pass ->
                    put(
                        EmotionBillboardRenderTypes.resolve(textureId, pass),
                        ByteBufferBuilder(EmotionBillboardRenderTypes.BUFFER_SIZE),
                    )
                }
            }
    }
    private val sharedBuffer = ByteBufferBuilder(EmotionBillboardRenderTypes.BUFFER_SIZE)
    private val bufferSource = MultiBufferSource.immediateWithBuffers(fixedBuffers, sharedBuffer)
    private var hasPendingGeometry = false

    fun consumer(textureId: String, pass: EmotionBillboardRenderPass): VertexConsumer {
        val renderType = EmotionBillboardRenderTypes.resolve(textureId, pass)
        fixedBuffers.computeIfAbsent(renderType) {
            ByteBufferBuilder(EmotionBillboardRenderTypes.BUFFER_SIZE)
        }
        val consumer = bufferSource.getBuffer(renderType)
        hasPendingGeometry = true
        return consumer
    }

    fun release(renderTypes: Collection<RenderType>) {
        renderTypes.forEach { renderType ->
            if (hasPendingGeometry) {
                bufferSource.endBatch(renderType)
            }
            fixedBuffers.remove(renderType)?.close()
        }
    }

    fun flush() {
        if (!hasPendingGeometry) {
            return
        }
        try {
            bufferSource.endBatch()
        } finally {
            hasPendingGeometry = false
        }
    }
}
