package com.voxymap.client.map;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.NormalRenderPipeline;

public class VoxyBridge {

    /** Blocks per unit of {@code VoxyConfig.sectionRenderDistance} (one top-level LOD node). */
    private static final double BLOCKS_PER_RENDER_DISTANCE_UNIT = 512.0;
    /** The furthest Voxy's own settings go. */
    private static final float MAX_RENDER_DISTANCE = 64.0f;
    /** Room for terrain below the focus height, which meets the screen edge further out. */
    private static final double RENDER_DISTANCE_MARGIN = 1.15;

    private static NormalRenderPipeline.FogMode previousFogMode = null;
    private static Float previousRenderDistance = null;

    /**
     * Voxy only draws within its render distance of the camera, measured across the ground,
     * and the map camera sits far behind and above what it looks at. While the map is open
     * this raises the render distance until it covers {@code blocks}, the furthest point on
     * screen; it never lowers it, so zooming back in does not drop and reload terrain. This
     * is what Voxy's own settings screen does when the slider moves.
     */
    public static void coverRenderDistanceForMap(double blocks) {
        try {
            var config = VoxyConfig.CONFIG;
            if (previousRenderDistance == null) {
                previousRenderDistance = config.sectionRenderDistance;
            }
            float needed = Double.isFinite(blocks)
                    ? (float) Math.ceil(blocks * RENDER_DISTANCE_MARGIN / BLOCKS_PER_RENDER_DISTANCE_UNIT)
                    : MAX_RENDER_DISTANCE;
            float target = Math.min(MAX_RENDER_DISTANCE, Math.max(previousRenderDistance, needed));
            if (target <= config.sectionRenderDistance) {
                return;
            }
            setRenderDistance(target);
        } catch (Throwable ignored) {
        }
    }

    public static void restoreRenderDistanceAfterMap() {
        if (previousRenderDistance == null) return;
        try {
            if (VoxyConfig.CONFIG.sectionRenderDistance != previousRenderDistance) {
                setRenderDistance(previousRenderDistance);
            }
        } catch (Throwable ignored) {
        }
        previousRenderDistance = null;
    }

    private static void setRenderDistance(float value) {
        VoxyConfig.CONFIG.sectionRenderDistance = value;
        var renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem != null) {
            renderSystem.setRenderDistance(value);
        }
    }

    public static void suppressEnvironmentalFogForMap() {
        // OFF drops both Voxy's own fog and the vanilla environmental fog over LODs.
        setFogMode(NormalRenderPipeline.FogMode.OFF, true);
    }

    public static void restoreEnvironmentalFogAfterMap() {
        if (previousFogMode == null) return;
        setFogMode(previousFogMode, false);
        previousFogMode = null;
    }

    private static void setFogMode(NormalRenderPipeline.FogMode value, boolean rememberPrevious) {
        try {
            var config = VoxyConfig.CONFIG;
            NormalRenderPipeline.FogMode current = config.getFogMode();
            if (rememberPrevious && previousFogMode == null) {
                previousFogMode = current;
            }
            if (current == value) {
                return;
            }
            config.setFogMode(value);
        } catch (Throwable ignored) {
        }
    }

}
