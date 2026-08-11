package me.whish.emotify.fabric.mixin.client;

import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
@SuppressWarnings("unused")
abstract class LevelRendererMixin {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
            shift = At.Shift.AFTER
        )
    )
    private void emotify$afterWorldFrameGraph(CallbackInfo callbackInfo) {
        EmotionBillboardRenderer.flushAfterWorldRendering();
    }
}
