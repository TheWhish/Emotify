package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.SharedConstants
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap

@Suppress("unused")
class EmotionBillboardRenderTypesTest : FunSpec({
    val textureIds = EmotionPresentationCatalog.ordered
        .map(EmotionPresentation::textureId)
        .distinct()

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("billboards use the final world targets without entity samplers") {
        EmotionBillboardRenderPass.entries.forEach { pass ->
            val pipeline = EmotionBillboardRenderTypes.renderPipeline(pass)
            pipeline.vertexFormat shouldBe DefaultVertexFormat.POSITION_TEX_COLOR
            pipeline.vertexFormatMode shouldBe VertexFormat.Mode.QUADS
            pipeline.samplers shouldBe listOf("Sampler0")
            pipeline.shaderDefines.flags() shouldBe emptySet()
            pipeline.depthTestFunction shouldBe DepthTestFunction.LEQUAL_DEPTH_TEST
            pipeline.blendFunction.isPresent shouldBe true
            pipeline.isCull shouldBe false
            pipeline.isWriteColor shouldBe true
            pipeline.isWriteAlpha shouldBe true
            pipeline.isWriteDepth shouldBe false
        }
        textureIds.forEach { textureId ->
            EmotionBillboardRenderPass.entries.forEach { pass ->
                val renderType = EmotionBillboardRenderTypes.resolve(textureId, pass)
                val setup = renderType.setupContract()

                renderType.pipeline() shouldBe EmotionBillboardRenderTypes.renderPipeline(pass)
                renderType.format() shouldBe DefaultVertexFormat.POSITION_TEX_COLOR
                renderType.mode() shouldBe VertexFormat.Mode.QUADS
                renderType.bufferSize() shouldBe EXPECTED_BUFFER_SIZE
                renderType.sortOnUpload() shouldBe true
                renderType.affectsCrumbling() shouldBe false
                renderType.outline().isEmpty shouldBe true
                setup.outputTarget shouldBe when (pass) {
                    EmotionBillboardRenderPass.COMPOSITED -> OutputTarget.WEATHER_TARGET
                    EmotionBillboardRenderPass.FINAL_DIRECT -> OutputTarget.MAIN_TARGET
                }
                setup.textureSamplers shouldBe setOf("Sampler0")
                setup.useLightmap shouldBe false
                setup.useOverlay shouldBe false
            }
        }
    }

    test("world billboard render types are cached per texture") {
        textureIds.forEach { textureId ->
            EmotionBillboardRenderPass.entries.forEach { pass ->
                (EmotionBillboardRenderTypes.resolve(textureId, pass) ===
                    EmotionBillboardRenderTypes.resolve(textureId, pass)) shouldBe true
            }
        }
    }

    test("local texture retention does not evict remote render types") {
        val retainedLocal = "emotify_custom:retained"
        val staleLocal = "emotify_custom:stale"
        val remote = "emotify_custom:remote/shared"
        fun resolve(textureId: String) = EmotionBillboardRenderTypes.resolve(
            textureId,
            Identifier.fromNamespaceAndPath("emotify", "test/${textureId.substringAfter(':')}"),
            EmotionBillboardRenderPass.FINAL_DIRECT,
        )
        val retainedRenderType = resolve(retainedLocal)
        val staleRenderType = resolve(staleLocal)
        val remoteRenderType = resolve(remote)

        EmotionBillboardRenderTypes.retainLocalCustomTextures(setOf(retainedLocal))

        (resolve(retainedLocal) === retainedRenderType) shouldBe true
        (resolve(staleLocal) === staleRenderType) shouldBe false
        (resolve(remote) === remoteRenderType) shouldBe true
        EmotionBillboardRenderTypes.releaseCustomTexture(retainedLocal)
        EmotionBillboardRenderTypes.releaseCustomTexture(remote)
    }

    test("local texture retention evicts render types created only for final direct rendering") {
        val staleLocal = "emotify_custom:final_direct_stale"
        fun resolve() = EmotionBillboardRenderTypes.resolve(
            staleLocal,
            Identifier.fromNamespaceAndPath("emotify", "test/final_direct_stale"),
            EmotionBillboardRenderPass.FINAL_DIRECT,
        )
        val staleRenderType = resolve()

        EmotionBillboardRenderTypes.retainLocalCustomTextures(emptySet())

        (resolve() === staleRenderType) shouldBe false
        EmotionBillboardRenderTypes.releaseCustomTexture(staleLocal)
    }
})

private data class RenderSetupContract(
    val outputTarget: OutputTarget,
    val textureSamplers: Set<String>,
    val useLightmap: Boolean,
    val useOverlay: Boolean,
)

private fun RenderType.setupContract(): RenderSetupContract {
    val setup = renderTypeStateField.get(this) as RenderSetup
    val textures = renderSetupTexturesField.get(setup) as? Map<*, *>
        ?: error("Render setup textures must be a map")
    return RenderSetupContract(
        outputTarget = renderSetupOutputTargetField.get(setup) as OutputTarget,
        textureSamplers = textures.keys.mapTo(linkedSetOf()) { sampler ->
            sampler as? String ?: error("Render setup sampler names must be strings")
        },
        useLightmap = renderSetupUseLightmapField.getBoolean(setup),
        useOverlay = renderSetupUseOverlayField.getBoolean(setup),
    )
}

private val renderTypeStateField = accessibleField(RenderType::class.java, "state")
private val renderSetupOutputTargetField = accessibleField(RenderSetup::class.java, "outputTarget")
private val renderSetupTexturesField = accessibleField(RenderSetup::class.java, "textures")
private val renderSetupUseLightmapField = accessibleField(RenderSetup::class.java, "useLightmap")
private val renderSetupUseOverlayField = accessibleField(RenderSetup::class.java, "useOverlay")

private fun accessibleField(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply {
    check(trySetAccessible()) { "Cannot inspect ${owner.name}.$name" }
}

private const val EXPECTED_BUFFER_SIZE = 1_536
