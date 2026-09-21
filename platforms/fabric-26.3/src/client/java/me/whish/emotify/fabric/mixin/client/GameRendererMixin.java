package me.whish.emotify.fabric.mixin.client;

import me.whish.emotify.client.EmotionPickerController;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
@SuppressWarnings("unused")
abstract class GameRendererMixin {
    @Inject(method = "render()V", at = @At("TAIL"))
    private void emotify$afterRender(CallbackInfo callbackInfo) {
        EmotionPickerController.onRenderFrame();
    }
}
