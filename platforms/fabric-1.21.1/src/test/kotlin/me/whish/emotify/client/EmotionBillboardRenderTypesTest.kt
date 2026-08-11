package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    test("composited billboards keep depth in the late translucent target") {
        textureIds.forEach { textureId ->
            val description = EmotionBillboardRenderTypes.resolve(
                textureId,
                EmotionBillboardRenderPass.COMPOSITED,
            ).toString()

            description shouldContain "translucent_transparency"
            description shouldContain "depth_test[<=]"
            description shouldContain "particles_target"
            description shouldContain "write_mask_state[writeColor=true, writeDepth=true]"
        }
    }

    test("final direct billboards depth test without obscuring later translucent pixels") {
        textureIds.forEach { textureId ->
            val description = EmotionBillboardRenderTypes.resolve(
                textureId,
                EmotionBillboardRenderPass.FINAL_DIRECT,
            ).toString()

            description shouldContain "translucent_transparency"
            description shouldContain "depth_test[<=]"
            description shouldContain "main_target"
            description shouldContain "write_mask_state[writeColor=true, writeDepth=false]"
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
            EmotionBillboardRenderPass.COMPOSITED,
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
