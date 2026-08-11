package me.whish.emotify.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
@SuppressWarnings({"unused", "ConstantValue"})
abstract class PlayerRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("TAIL")
    )
    private void emotify$afterRender(
        LivingEntityRenderState renderState,
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        CallbackInfo callbackInfo
    ) {
        if (renderState instanceof PlayerRenderState playerRenderState && (Object) this instanceof PlayerRenderer renderer) {
            EmotionBillboardRenderer.render(playerRenderState, poseStack, renderer);
        }
    }
}
