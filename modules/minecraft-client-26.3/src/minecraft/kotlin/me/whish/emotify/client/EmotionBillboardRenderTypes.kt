package me.whish.emotify.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

object EmotionBillboardRenderTypes {
    private val location = Identifier.fromNamespaceAndPath("emotify", "emotion_billboard")
    private val shader = Identifier.fromNamespaceAndPath("emotify", "core/emotion_billboard")
    private val baseSnippet = RenderPipeline.builder()
        .withVertexShader(shader)
        .withFragmentShader(shader)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .withCull(false)
        .buildSnippet()
    private val pipeline = RenderPipeline
        .builder(RenderPipelines.GLOBALS_SNIPPET, baseSnippet)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withLocation(location)
        .build()
    @Suppress("DEPRECATION")
    private val oitPipelines = OitPipelineSet.Builder(baseSnippet, "emotify_emotion_billboard").build()
    private val builtInTextureIds = EmotionPresentationCatalog.ordered
        .mapTo(HashSet(), EmotionPresentation::textureId)
    private val renderTypesByTextureId = createRenderTypes().toMutableMap()

    fun resolve(textureId: String): RenderType = resolve(textureId, EmotionTextureResources.resolve(textureId))

    fun pipeline(): RenderPipeline = pipeline

    fun oitPipelines(): OitPipelineSet = oitPipelines

    fun resolve(textureId: String, texture: Identifier): RenderType =
        renderTypesByTextureId.getOrPut(textureId) { create(texture) }

    fun retainLocalCustomTextures(textureIds: Set<String>) {
        renderTypesByTextureId.keys.removeIf { textureId ->
            isLocalCustomTexture(textureId) && textureId !in textureIds
        }
    }

    fun releaseCustomTexture(textureId: String) {
        if (textureId !in builtInTextureIds) {
            renderTypesByTextureId.remove(textureId)
        }
    }

    private fun isLocalCustomTexture(textureId: String): Boolean =
        textureId.startsWith(LOCAL_CUSTOM_TEXTURE_PREFIX) && !textureId.startsWith(REMOTE_CUSTOM_TEXTURE_PREFIX)

    private fun createRenderTypes(): Map<String, RenderType> = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered
            .map(EmotionPresentation::textureId)
            .distinct()
            .associateWith { textureId -> create(EmotionTextureResources.resolve(textureId)) },
    )

    private fun create(texture: Identifier): RenderType =
        RenderType.create(
            "emotify_emotion_billboard",
            RenderSetup.builder(pipeline)
                .withTexture(PRIMARY_TEXTURE_SAMPLER, texture)
                .setOitPipelines(oitPipelines)
                .sortOnUpload()
                .createRenderSetup(),
        )

    private const val PRIMARY_TEXTURE_SAMPLER = "Sampler0"
    private const val LOCAL_CUSTOM_TEXTURE_PREFIX = "emotify_custom:"
    private const val REMOTE_CUSTOM_TEXTURE_PREFIX = "emotify_custom:remote/"
}
