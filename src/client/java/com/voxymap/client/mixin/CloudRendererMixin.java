package com.voxymap.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clouds are drawn from the translucent stage of the level render, either directly or
 * through order-independent transparency, depending on the graphics settings.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class CloudRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$hideClouds(CloudStatus cloudStatus, RenderPass renderPass, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$hideOitClouds(CloudStatus cloudStatus, OitStage stage, GpuTextureView depthTexture,
                                       OitRenderPassProvider.Parameters parameters, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
