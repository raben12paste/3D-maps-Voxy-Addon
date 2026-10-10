package com.voxymap.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.voxymap.client.map.VoxyMapCameraController;
import com.voxymap.client.map.VoxyMapGuiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererMixin {
    @Shadow(remap = false)
    @Final
    private Minecraft minecraft;

    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.BEFORE),
            remap = false
    )
    private void voxymap$renderGuiMapBeforeHud(CallbackInfo ci) {
        VoxyMapGuiRenderer.render(minecraft);
    }

    @Inject(method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$hideHandInMap(CameraRenderState cameraRenderState, PlayerRenderState playerRenderState, GpuTextureView colorTarget, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
