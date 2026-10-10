package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapGuiRenderer;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin {
    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$renderOnlyInMapGui(Viewport<?> viewport, int depthTexture, int colorTexture, CallbackInfo ci) {
        if (VoxyMapGuiRenderer.shouldCancelWorldRender()) {
            ci.cancel();
        }
    }
}
