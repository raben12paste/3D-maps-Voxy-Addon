package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Level.class, remap = false)
public abstract class LevelMixin {
    private boolean voxymap$isClientMapWeatherOverrideActive() {
        return VoxyMapCameraController.isActive()
                && (Object) this instanceof ClientLevel;
    }

    @Inject(method = "isDarkOutside", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$keepMapBrightOutside(CallbackInfoReturnable<Boolean> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$keepMapSkyBright(CallbackInfoReturnable<Integer> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = {"getOverworldClockTime", "getDefaultClockTime"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$forceDayForMap(CallbackInfoReturnable<Long> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(6000L);
        }
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$clearRainForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$clearThunderForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$clearRainingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$clearThunderingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(false);
        }
    }
}
