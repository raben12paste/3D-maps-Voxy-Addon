package com.voxymap.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Shadow
    private GpuBuffer emptyBuffer;

    @Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
    private void voxymap$clearMapFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float skyDarken, ClientLevel level, CallbackInfoReturnable<Vector4f> cir) {
        if (VoxyMapCameraController.isActive()) {
            if (level != null && "minecraft:the_end".equals(level.dimension().identifier().toString())) {
                cir.setReturnValue(new Vector4f(0.02f, 0.015f, 0.035f, 1.0f));
                return;
            }
            cir.setReturnValue(new Vector4f(0.62f, 0.74f, 0.93f, 1.0f));
        }
    }

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void voxymap$disableFogInMap(FogRenderer.FogMode fogMode, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(emptyBuffer.slice(0L, FogRenderer.FOG_UBO_SIZE));
        }
    }
}
