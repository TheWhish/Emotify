package me.whish.emotify.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import java.util.Objects;
import me.whish.emotify.client.presentation.EmotionPresentation;
import me.whish.emotify.client.presentation.EmotionPresentationCatalog;
import me.whish.emotify.domain.AnimationMotion;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmotionBillboardSubmissionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void keepsDepthAndReusesTheCachedRenderType() {
        assertKeepsDepthWithoutCulling(EmotionBillboardRenderTypes.INSTANCE.pipeline());

        RenderType first = EmotionBillboardRenderTypes.INSTANCE.resolve("emotify:textures/emotions/faces.png");
        RenderType second = EmotionBillboardRenderTypes.INSTANCE.resolve("emotify:textures/emotions/faces.png");
        assertSame(first, second);
        assertTrue(first.hasBlending());
        assertTrue(first.sortOnUpload());
    }

    @Test
    void providesEveryImprovedTransparencyPipelineWithTheSameDepthContract() {
        OitPipelineSet pipelines = EmotionBillboardRenderTypes.INSTANCE.oitPipelines();
        List.of(pipelines.depthBoundsPipeline(), pipelines.transmittancePipeline(), pipelines.accumulatePipeline())
            .forEach(EmotionBillboardSubmissionTest::assertKeepsDepthWithoutCulling);
    }

    @Test
    void submitsTheBillboardAfterTranslucentTerrain() {
        SubmitNodeStorage storage = new SubmitNodeStorage();
        EmotionPresentation presentation = EmotionPresentationCatalog.INSTANCE.getOrdered().getFirst();

        EmotionBillboardGeometry.INSTANCE.submit(
            storage,
            new PoseStack(),
            presentation.getTextureId(),
            presentation.regionAt(0L),
            0.0,
            AnimationMotion.FULL,
            0L
        );

        SubmitNodeCollection collection = storage.order(0);
        assertFalse(collection.afterTerrain.isEmpty());
        assertTrue(collection.translucentCustomGeometry.isEmpty());
    }

    private static void assertKeepsDepthWithoutCulling(RenderPipeline pipeline) {
        DepthStencilState depth = Objects.requireNonNull(pipeline.getDepthStencilState());
        assertSame(CompareOp.GREATER_THAN_OR_EQUAL, depth.depthTest());
        assertFalse(depth.writeDepth());
        assertFalse(pipeline.isCull());
    }
}
