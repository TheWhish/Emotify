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
    @Inject(method = "lambda$addWeatherPass$0", at = @At("RETURN"))
    private void emotify$afterWeather(CallbackInfo callbackInfo) {
        EmotionBillboardRenderer.flushAfterWeather();
    }
}
