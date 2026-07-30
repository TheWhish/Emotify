package me.whish.emotify.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.whish.emotify.client.EmotionBillboardRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
abstract class PlayerRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("TAIL")
    )
    private void emotify$afterRender(
        AbstractClientPlayer player,
        float entityYaw,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        CallbackInfo callbackInfo
    ) {
        EmotionBillboardRenderer.render(
            player,
            partialTick,
            poseStack,
            multiBufferSource,
            packedLight,
            (PlayerRenderer) (Object) this
        );
    }

    @Inject(
        method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void emotify$beforeRenderNameTag(
        AbstractClientPlayer player,
        Component component,
        PoseStack poseStack,
        MultiBufferSource multiBufferSource,
        int packedLight,
        float partialTick,
        CallbackInfo callbackInfo
    ) {
        if (EmotionBillboardRenderer.shouldHideNameTag(player)) {
            callbackInfo.cancel();
        }
    }
}
