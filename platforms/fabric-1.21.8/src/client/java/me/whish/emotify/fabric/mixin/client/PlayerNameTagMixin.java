package me.whish.emotify.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
@SuppressWarnings("unused")
abstract class PlayerNameTagMixin {
    @Inject(
        method = "renderNameTag(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void emotify$beforeRenderNameTag(
        PlayerRenderState renderState,
        Component component,
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        CallbackInfo callbackInfo
    ) {
        if (EmotionBillboardRenderer.shouldHideNameTag(renderState)) {
            callbackInfo.cancel();
        }
    }
}
