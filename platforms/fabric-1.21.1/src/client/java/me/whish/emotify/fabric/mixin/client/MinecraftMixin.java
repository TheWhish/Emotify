package me.whish.emotify.fabric.mixin.client;

import me.whish.emotify.client.CustomEmojiCopyController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void emotify$copyCustomEmoji(CallbackInfo callbackInfo) {
        if (CustomEmojiCopyController.intercept((Minecraft) (Object) this)) {
            callbackInfo.cancel();
        }
    }
}
