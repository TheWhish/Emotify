package me.whish.emotify.neoforge.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.whish.emotify.client.EmotionSearchBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EditBox.class)
@SuppressWarnings({
    "unused",
    "MixinAnnotationTarget",
    "InvalidInjectorMethodSignature"
})
abstract class EditBoxHintMixin1211 {
    @WrapOperation(
        method = "renderWidget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"
        )
    )
    private int emotify$drawHint(
        GuiGraphics graphics,
        Font font,
        Component text,
        int x,
        int y,
        int color,
        Operation<Integer> original
    ) {
        if ((Object) this instanceof EmotionSearchBox) {
            return graphics.drawString(font, text, x, y, color, false);
        }
        return original.call(graphics, font, text, x, y, color);
    }
}
