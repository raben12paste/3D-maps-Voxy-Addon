package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void voxymap$forceDayForMap(CallbackInfoReturnable<Long> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(6000L);
        }
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearRainForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearThunderForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearRainingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearThunderingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(false);
        }
    }
}
