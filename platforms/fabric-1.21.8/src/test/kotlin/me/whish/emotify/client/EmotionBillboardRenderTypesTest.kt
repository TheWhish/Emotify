package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
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

    test("billboards use the flat textured shader without entity samplers") {
        EmotionBillboardRenderPass.entries.forEach { pass ->
            val pipeline = EmotionBillboardRenderTypes.renderPipeline(pass)
            pipeline.vertexFormat shouldBe DefaultVertexFormat.POSITION_TEX_COLOR
            pipeline.samplers shouldBe listOf("Sampler0")
            pipeline.shaderDefines.flags() shouldBe emptySet()
            pipeline.depthTestFunction shouldBe DepthTestFunction.LEQUAL_DEPTH_TEST
            pipeline.blendFunction.isPresent shouldBe true
            pipeline.isCull shouldBe false
            pipeline.isWriteColor shouldBe true
            pipeline.isWriteAlpha shouldBe true
        }
        EmotionBillboardRenderTypes.renderPipeline(EmotionBillboardRenderPass.COMPOSITED).isWriteDepth shouldBe true
        EmotionBillboardRenderTypes.renderPipeline(EmotionBillboardRenderPass.FINAL_DIRECT).isWriteDepth shouldBe false
        textureIds.forEach { textureId ->
            val composited = EmotionBillboardRenderTypes.resolve(
                textureId,
                EmotionBillboardRenderPass.COMPOSITED,
            ).toString()
            val finalDirect = EmotionBillboardRenderTypes.resolve(
                textureId,
                EmotionBillboardRenderPass.FINAL_DIRECT,
            ).toString()

            composited shouldContain "particles_target"
            finalDirect shouldContain "main_target"
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
            ResourceLocation.fromNamespaceAndPath("emotify", "test/${textureId.substringAfter(':')}"),
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
            ResourceLocation.fromNamespaceAndPath("emotify", "test/final_direct_stale"),
            EmotionBillboardRenderPass.FINAL_DIRECT,
        )
        val staleRenderType = resolve()

        EmotionBillboardRenderTypes.retainLocalCustomTextures(emptySet())

        (resolve() === staleRenderType) shouldBe false
        EmotionBillboardRenderTypes.releaseCustomTexture(staleLocal)
    }
})
