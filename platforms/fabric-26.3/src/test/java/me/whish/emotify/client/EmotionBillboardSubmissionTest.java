package me.whish.emotify.client;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import java.util.Objects;
import net.minecraft.SharedConstants;
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
        DepthStencilState depth = Objects.requireNonNull(
            EmotionBillboardRenderTypes.INSTANCE.pipeline().getDepthStencilState()
        );
        assertSame(CompareOp.GREATER_THAN_OR_EQUAL, depth.depthTest());
        assertFalse(depth.writeDepth());
        assertFalse(EmotionBillboardRenderTypes.INSTANCE.pipeline().isCull());

        RenderType first = EmotionBillboardRenderTypes.INSTANCE.resolve("emotify:textures/emotions/faces.png");
        RenderType second = EmotionBillboardRenderTypes.INSTANCE.resolve("emotify:textures/emotions/faces.png");
        assertSame(first, second);
        assertTrue(first.hasBlending());
        assertTrue(first.sortOnUpload());
    }
}
