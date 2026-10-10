package com.voxymap.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxymap.client.integration.VoxyMapReturnOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroGuiMapMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"), remap = false, require = 0)
    private void voxymap$renderReturnButton(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        VoxyMapReturnOverlay.render(g, Minecraft.getInstance().getWindow().getGuiScaledWidth(), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void voxymap$clickReturnButton(MouseButtonEvent event, boolean isInside, CallbackInfoReturnable<Boolean> cir) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && VoxyMapReturnOverlay.click(width, event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }
}
