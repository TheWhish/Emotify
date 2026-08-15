package me.whish.emotify.client

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

enum class EmotionBillboardRenderPass {
    COMPOSITED,
    FINAL_DIRECT,
}

object EmotionBillboardRenderTypes {
    private val pipeline = RenderPipeline
        .builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("emotify", "emotion_billboard"))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .withCull(false)
        .build()
    private val builtInTextureIds = EmotionPresentationCatalog.ordered
        .mapTo(HashSet(), EmotionPresentation::textureId)
    private val compositedByTextureId = createRenderTypes(EmotionBillboardRenderPass.COMPOSITED).toMutableMap()
    private val finalDirectByTextureId = createRenderTypes(EmotionBillboardRenderPass.FINAL_DIRECT).toMutableMap()

    fun resolve(textureId: String, pass: EmotionBillboardRenderPass): RenderType =
        resolve(textureId, EmotionTextureResources.resolve(textureId), pass)

    fun pipeline(): RenderPipeline = pipeline

    fun resolve(textureId: String, texture: Identifier, pass: EmotionBillboardRenderPass): RenderType {
        val renderTypes = when (pass) {
            EmotionBillboardRenderPass.COMPOSITED -> compositedByTextureId
            EmotionBillboardRenderPass.FINAL_DIRECT -> finalDirectByTextureId
        }
        return renderTypes.getOrPut(textureId) {
            create(texture, pass)
        }
    }

    fun retainLocalCustomTextures(textureIds: Set<String>) {
        compositedByTextureId.keys.removeIf { textureId ->
            isLocalCustomTexture(textureId) && textureId !in textureIds
        }
        finalDirectByTextureId.keys.removeIf { textureId ->
            isLocalCustomTexture(textureId) && textureId !in textureIds
        }
    }

    fun releaseCustomTexture(textureId: String) {
        if (textureId !in builtInTextureIds) {
            compositedByTextureId.remove(textureId)
            finalDirectByTextureId.remove(textureId)
        }
    }

    private fun isLocalCustomTexture(textureId: String): Boolean =
        textureId.startsWith(LOCAL_CUSTOM_TEXTURE_PREFIX) && !textureId.startsWith(REMOTE_CUSTOM_TEXTURE_PREFIX)

    private fun createRenderTypes(pass: EmotionBillboardRenderPass): Map<String, RenderType> = java.util.Map.copyOf(
        EmotionPresentationCatalog.ordered
            .map(EmotionPresentation::textureId)
            .distinct()
            .associateWith { textureId ->
                create(EmotionTextureResources.resolve(textureId), pass)
            },
    )

    private fun create(texture: Identifier, pass: EmotionBillboardRenderPass): RenderType =
        RenderType.create(
            when (pass) {
                EmotionBillboardRenderPass.COMPOSITED -> "emotify_emotion_billboard_composited"
                EmotionBillboardRenderPass.FINAL_DIRECT -> "emotify_emotion_billboard_final_direct"
            },
            RenderSetup.builder(pipeline)
                .withTexture(PRIMARY_TEXTURE_SAMPLER, texture)
                .setOutputTarget(
                    when (pass) {
                        EmotionBillboardRenderPass.COMPOSITED -> OutputTarget.WEATHER_TARGET
                        EmotionBillboardRenderPass.FINAL_DIRECT -> OutputTarget.MAIN_TARGET
                    },
                )
                .sortOnUpload()
                .createRenderSetup(),
        )

    private const val PRIMARY_TEXTURE_SAMPLER = "Sampler0"
    private const val LOCAL_CUSTOM_TEXTURE_PREFIX = "emotify_custom:"
    private const val REMOTE_CUSTOM_TEXTURE_PREFIX = "emotify_custom:remote/"
}
