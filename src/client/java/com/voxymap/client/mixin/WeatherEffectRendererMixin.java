package com.voxymap.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rain and snow are drawn from the translucent stage of the level render, either directly
 * or through order-independent transparency, depending on the graphics settings.
 */
@Mixin(value = WeatherEffectRenderer.class, remap = false)
public abstract class WeatherEffectRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$hideWeather(WeatherRenderState weatherRenderState, RenderPass renderPass, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$hideOitWeather(OitStage stage, WeatherRenderState weatherRenderState, RenderPass renderPass, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
