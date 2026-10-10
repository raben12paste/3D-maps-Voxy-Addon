package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapCameraController;
import me.cortex.voxy.common.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Logger.class, remap = false)
public abstract class VoxyLoggerMixin {
    /**
     * Flying the map camera across not-yet-loaded terrain makes Voxy's hierarchical
     * {@code NodeManager} emit bursts of "Tried processing request for pos: ... but its
     * type was a request, ignoring!" warnings — the GPU traversal re-emits LOD requests
     * that are already in flight, and the duplicate is simply dropped. Voxy's
     * {@code Logger.error} mirrors the message into the chat HUD, which floods the chat
     * while browsing the map. Only the chat mirror is cancelled here, and only while the
     * map is open; the messages still reach the log file through slf4j. This runs on
     * Voxy's async node-manager thread, hence the volatile map-active flag.
     */
    @Inject(method = "showInHUD", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxymap$muteNodeRequestSpamOnMap(String message, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive() && message != null && message.contains("Tried processing")) {
            ci.cancel();
        }
    }
}
