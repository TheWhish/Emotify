package me.whish.emotify.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

enum class EmotionBillboardRenderPass {
    COMPOSITED,
    FINAL_DIRECT,
}

object EmotionBillboardRenderTypes {
    private val createRenderType: (
        String,
        VertexFormat,
        VertexFormat.Mode,
        Int,
        Boolean,
        Boolean,
        RenderType.CompositeState,
    ) -> RenderType = RenderType::create
    private val builtInTextureIds = EmotionPresentationCatalog.ordered
        .mapTo(HashSet(), EmotionPresentation::textureId)
    private val compositedByTextureId = createRenderTypes(EmotionBillboardRenderPass.COMPOSITED).toMutableMap()
    private val finalDirectByTextureId = createRenderTypes(EmotionBillboardRenderPass.FINAL_DIRECT).toMutableMap()

    fun resolve(textureId: String, pass: EmotionBillboardRenderPass): RenderType {
        return resolve(textureId, EmotionTextureResources.resolve(textureId), pass)
    }

    fun resolve(textureId: String, texture: ResourceLocation, pass: EmotionBillboardRenderPass): RenderType {
        val renderTypes = when (pass) {
            EmotionBillboardRenderPass.COMPOSITED -> compositedByTextureId
            EmotionBillboardRenderPass.FINAL_DIRECT -> finalDirectByTextureId
        }
        return renderTypes.getOrPut(textureId) {
            create(texture, pass)
        }
    }

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
        val writeMaskState = when (pass) {
            EmotionBillboardRenderPass.COMPOSITED -> RenderStateShard.COLOR_DEPTH_WRITE
            EmotionBillboardRenderPass.FINAL_DIRECT -> RenderStateShard.COLOR_WRITE
        }
        val state = RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setOverlayState(RenderStateShard.OVERLAY)
            .setOutputState(outputState)
            .setWriteMaskState(writeMaskState)
            .createCompositeState(false)
        return createRenderType(
            pass.renderTypeName,
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            true,
            state,
        )
    }

    internal const val BUFFER_SIZE = 1_536
    private const val LOCAL_CUSTOM_TEXTURE_PREFIX = "emotify_custom:"
    private const val REMOTE_CUSTOM_TEXTURE_PREFIX = "emotify_custom:remote/"

    private val EmotionBillboardRenderPass.renderTypeName: String
        get() = when (this) {
            EmotionBillboardRenderPass.COMPOSITED -> "emotify_emotion_billboard_composited"
            EmotionBillboardRenderPass.FINAL_DIRECT -> "emotify_emotion_billboard_final_direct"
        }
}
