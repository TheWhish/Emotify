package me.whish.emotify.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
@SuppressWarnings("unused")
abstract class AvatarNameTagMixin {
    @Inject(
        method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void emotify$beforeSubmitNameTag(
        AvatarRenderState renderState,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState cameraRenderState,
        CallbackInfo callbackInfo
    ) {
        if (EmotionBillboardRenderer.shouldHideNameTag(renderState)) {
            callbackInfo.cancel();
        }
    }
}
