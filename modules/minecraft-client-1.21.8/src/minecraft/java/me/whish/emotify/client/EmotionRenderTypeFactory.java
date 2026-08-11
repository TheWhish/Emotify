package me.whish.emotify.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderType;

public final class EmotionRenderTypeFactory {
    private EmotionRenderTypeFactory() {
    }

    public static RenderType create(
        String name,
        int bufferSize,
        boolean affectsCrumbling,
        boolean sortOnUpload,
        RenderPipeline pipeline,
        RenderType.CompositeState state
    ) {
        return RenderType.create(name, bufferSize, affectsCrumbling, sortOnUpload, pipeline, state);
    }
}
