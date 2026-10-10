package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapCameraController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every client-side reader of a world clock ({@code Level} clock time, timelines, timed
 * environment attributes) goes through {@code ClockInstance.totalTicks()}. The clock still
 * advances underneath, since {@code ClientClockManager} ticks the field directly.
 */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance", remap = false)
public abstract class ClientClockInstanceMixin {
    @Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxymap$forceDayForMap(CallbackInfoReturnable<Long> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(6000L);
        }
    }
}
