package me.whish.emotify.client;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.rendertype.OutputTarget;
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
    void keepsDepthAndSelectsTheFinalWorldTarget() {
        DepthStencilState depth = Objects.requireNonNull(
            EmotionBillboardRenderTypes.INSTANCE.pipeline().getDepthStencilState()
        );
        assertSame(CompareOp.GREATER_THAN_OR_EQUAL, depth.depthTest());
        assertFalse(depth.writeDepth());
        assertFalse(EmotionBillboardRenderTypes.INSTANCE.pipeline().isCull());

        RenderType composited = EmotionBillboardRenderTypes.INSTANCE.resolve(
            "emotify:textures/emotions/faces.png",
            EmotionBillboardRenderPass.COMPOSITED
        );
        RenderType finalDirect = EmotionBillboardRenderTypes.INSTANCE.resolve(
            "emotify:textures/emotions/faces.png",
            EmotionBillboardRenderPass.FINAL_DIRECT
        );
        assertSame(OutputTarget.WEATHER_TARGET, composited.outputTarget());
        assertSame(OutputTarget.MAIN_TARGET, finalDirect.outputTarget());
        assertTrue(composited.hasBlending());
        assertTrue(finalDirect.hasBlending());
        assertTrue(composited.sortOnUpload());
        assertTrue(finalDirect.sortOnUpload());
    }
}
