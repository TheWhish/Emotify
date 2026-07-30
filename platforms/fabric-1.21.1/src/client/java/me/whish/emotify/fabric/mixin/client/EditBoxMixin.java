package me.whish.emotify.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.whish.emotify.client.EmotionSearchBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EditBox.class)
abstract class EditBoxMixin {
    @WrapOperation(
        method = "renderWidget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"
        )
    )
    private int emotify$drawFormattedText(
        GuiGraphics graphics,
        Font font,
        FormattedCharSequence text,
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

    @WrapOperation(
        method = "renderWidget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"
        )
    )
    private int emotify$drawSuggestion(
        GuiGraphics graphics,
        Font font,
        String text,
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
