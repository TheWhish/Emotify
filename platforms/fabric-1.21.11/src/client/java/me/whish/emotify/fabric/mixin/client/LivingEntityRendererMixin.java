package me.whish.emotify.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
@SuppressWarnings({"unused", "ConstantValue"})
abstract class LivingEntityRendererMixin {
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
        at = @At("TAIL")
    )
    private void emotify$afterSubmit(
        LivingEntityRenderState renderState,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState cameraRenderState,
        CallbackInfo callbackInfo
    ) {
        if (
            renderState instanceof AvatarRenderState avatarRenderState &&
            (Object) this instanceof AvatarRenderer<?> avatarRenderer
        ) {
            EmotionBillboardRenderer.render(
                avatarRenderState,
                poseStack,
                submitNodeCollector,
                cameraRenderState,
                avatarRenderer
            );
        }
    }
}
