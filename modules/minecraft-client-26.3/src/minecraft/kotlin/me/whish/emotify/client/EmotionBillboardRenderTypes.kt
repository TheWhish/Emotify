package me.whish.emotify.client

import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

object EmotionBillboardRenderTypes {
    private val pipeline = RenderPipeline
        .builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("emotify", "emotion_billboard"))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .withCull(false)
        .build()
    private val builtInTextureIds = EmotionPresentationCatalog.ordered
        .mapTo(HashSet(), EmotionPresentation::textureId)
    private val renderTypesByTextureId = createRenderTypes().toMutableMap()

    fun resolve(textureId: String): RenderType = resolve(textureId, EmotionTextureResources.resolve(textureId))

    fun pipeline(): RenderPipeline = pipeline

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
                .sortOnUpload()
                .createRenderSetup(),
        )

    private const val PRIMARY_TEXTURE_SAMPLER = "Sampler0"
    private const val LOCAL_CUSTOM_TEXTURE_PREFIX = "emotify_custom:"
    private const val REMOTE_CUSTOM_TEXTURE_PREFIX = "emotify_custom:remote/"
}
