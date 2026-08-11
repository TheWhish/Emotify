package me.whish.emotify.client

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

enum class EmotionBillboardRenderPass {
    COMPOSITED,
    FINAL_DIRECT,
}

object EmotionBillboardRenderTypes {
    private val compositedPipeline = createPipeline(EmotionBillboardRenderPass.COMPOSITED)
    private val finalDirectPipeline = createPipeline(EmotionBillboardRenderPass.FINAL_DIRECT)
    private val builtInTextureIds = EmotionPresentationCatalog.ordered
        .mapTo(HashSet(), EmotionPresentation::textureId)
    private val compositedByTextureId = createRenderTypes(EmotionBillboardRenderPass.COMPOSITED).toMutableMap()
    private val finalDirectByTextureId = createRenderTypes(EmotionBillboardRenderPass.FINAL_DIRECT).toMutableMap()

    fun resolve(textureId: String, pass: EmotionBillboardRenderPass): RenderType =
        resolve(textureId, EmotionTextureResources.resolve(textureId), pass)

    fun resolve(textureId: String, texture: ResourceLocation, pass: EmotionBillboardRenderPass): RenderType {
        val renderTypes = when (pass) {
            EmotionBillboardRenderPass.COMPOSITED -> compositedByTextureId
            EmotionBillboardRenderPass.FINAL_DIRECT -> finalDirectByTextureId
        }
        return renderTypes.getOrPut(textureId) {
            create(texture, pass)
        }
    }

    fun renderPipeline(pass: EmotionBillboardRenderPass): RenderPipeline = pass.pipeline

    fun retainLocalCustomTextures(textureIds: Set<String>) {
        val stale = HashSet<String>()
        compositedByTextureId.keys.filterTo(stale) { textureId ->
            isStaleLocalCustomTexture(textureId, textureIds)
        }
        finalDirectByTextureId.keys.filterTo(stale) { textureId ->
            isStaleLocalCustomTexture(textureId, textureIds)
        }
        stale.forEach(::releaseCustomTexture)
    }

    fun releaseCustomTexture(textureId: String) {
        if (textureId in builtInTextureIds) {
            return
        }
        val removed = listOfNotNull(
            compositedByTextureId.remove(textureId),
            finalDirectByTextureId.remove(textureId),
        )
        EmotionBillboardDeferredBuffer.release(removed)
    }

    private fun isLocalCustomTexture(textureId: String): Boolean =
        textureId.startsWith(LOCAL_CUSTOM_TEXTURE_PREFIX) && !textureId.startsWith(REMOTE_CUSTOM_TEXTURE_PREFIX)

    private fun isStaleLocalCustomTexture(textureId: String, retainedTextureIds: Set<String>): Boolean =
        isLocalCustomTexture(textureId) && textureId !in retainedTextureIds

    private fun createRenderTypes(pass: EmotionBillboardRenderPass): Map<String, RenderType> = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered
            .map(EmotionPresentation::textureId)
            .distinct()
            .associateWith { textureId ->
                create(EmotionTextureResources.resolve(textureId), pass)
            },
    )

    private fun create(texture: ResourceLocation, pass: EmotionBillboardRenderPass): RenderType {
        val outputState = when (pass) {
            EmotionBillboardRenderPass.COMPOSITED -> RenderStateShard.PARTICLES_TARGET
            EmotionBillboardRenderPass.FINAL_DIRECT -> RenderStateShard.MAIN_TARGET
        }
        val state = RenderType.CompositeState.builder()
            .setTextureState(RenderStateShard.TextureStateShard(texture, false))
            .setOutputState(outputState)
            .createCompositeState(false)
        return EmotionRenderTypeFactory.create(
            pass.renderTypeName,
            BUFFER_SIZE,
            false,
            true,
            pass.pipeline,
            state,
        )
    }

    private fun createPipeline(pass: EmotionBillboardRenderPass): RenderPipeline = RenderPipeline
        .builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(ResourceLocation.fromNamespaceAndPath("emotify", pass.pipelineName))
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withCull(false)
        .withDepthWrite(pass == EmotionBillboardRenderPass.COMPOSITED)
        .build()

    internal const val BUFFER_SIZE = 1_536
    private const val LOCAL_CUSTOM_TEXTURE_PREFIX = "emotify_custom:"
    private const val REMOTE_CUSTOM_TEXTURE_PREFIX = "emotify_custom:remote/"

    private val EmotionBillboardRenderPass.renderTypeName: String
        get() = when (this) {
            EmotionBillboardRenderPass.COMPOSITED -> "emotify_emotion_billboard_composited"
            EmotionBillboardRenderPass.FINAL_DIRECT -> "emotify_emotion_billboard_final_direct"
        }

    private val EmotionBillboardRenderPass.pipelineName: String
        get() = when (this) {
            EmotionBillboardRenderPass.COMPOSITED -> "emotion_billboard_composited"
            EmotionBillboardRenderPass.FINAL_DIRECT -> "emotion_billboard_final_direct"
        }

    private val EmotionBillboardRenderPass.pipeline: RenderPipeline
        get() = when (this) {
            EmotionBillboardRenderPass.COMPOSITED -> compositedPipeline
            EmotionBillboardRenderPass.FINAL_DIRECT -> finalDirectPipeline
        }
}
