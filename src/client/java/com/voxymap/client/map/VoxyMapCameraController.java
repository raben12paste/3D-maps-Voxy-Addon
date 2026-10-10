package com.voxymap.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

public final class VoxyMapCameraController {
    /** The furthest zoom (blocks per pixel); past it the camera would only move further away. */
    public static final double MAX_BLOCKS_PER_PIXEL = 48.0;

    private static final double RANGE_PER_BLOCK_PER_PIXEL = 210.0;
    private static final float WIDE_FOV = 70.0f;
    private static final float FAR_FOV = 60.0f;
    private static final float FAR_PITCH = 82.0f;

    // Read from Voxy's async node-manager thread by VoxyLoggerMixin, not just the client thread.
    private static volatile boolean active;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static float cameraYaw;
    private static float cameraPitch;
    private static float fov;

    private VoxyMapCameraController() {
    }

    public static void update(Minecraft minecraft, double centerX, double centerY, double centerZ, double yawRadians, double pitchControl, double blocksPerPixel) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            active = false;
            return;
        }

        boolean endDimension = "minecraft:the_end".equals(minecraft.level.dimension().identifier().toString());
        float basePitch;
        double minRange;
        float minFov;
        if (endDimension) {
            basePitch = Mth.clamp((float) (28.0 + pitchControl * 34.0), 30.0f, 62.0f);
            minRange = 512.0;
            minFov = 14.0f;
        } else {
            basePitch = Mth.clamp((float) (18.0 + pitchControl * 42.0), 18.0f, 72.0f);
            minRange = 1024.0;
            minFov = 6.0f;
        }

        double desiredRange = Math.max(64.0, blocksPerPixel * RANGE_PER_BLOCK_PER_PIXEL);
        double zoomOutStart = minRange / RANGE_PER_BLOCK_PER_PIXEL;
        float pitchDegrees;
        double range;
        if (blocksPerPixel <= zoomOutStart) {
            // Zoomed in: the camera stays put and the lens narrows, which keeps the view
            // close to orthographic.
            pitchDegrees = basePitch;
            range = minRange;
            fov = Mth.clamp((float) (WIDE_FOV * desiredRange / minRange), minFov, WIDE_FOV);
        } else {
            // Zoomed out: back away and tilt towards straight down. At the base pitch the top
            // of the screen runs off towards the horizon, far past what Voxy draws around the
            // camera, and the camera itself ends up thousands of blocks behind the focus.
            double t = Mth.clamp(Math.log(blocksPerPixel / zoomOutStart) / Math.log(MAX_BLOCKS_PER_PIXEL / zoomOutStart), 0.0, 1.0);
            float s = (float) (t * t * (3.0 - 2.0 * t));
            pitchDegrees = Mth.lerp(s, basePitch, FAR_PITCH);
            fov = Mth.lerp(s, WIDE_FOV, FAR_FOV);
            // Narrowing the lens is made up for with distance, so the scale on screen still follows the zoom.
            range = desiredRange * Math.tan(Math.toRadians(WIDE_FOV * 0.5)) / Math.tan(Math.toRadians(fov * 0.5));
        }
        float yawDegrees = (float) Math.toDegrees(yawRadians);

        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontalRange = Math.cos(pitchRadians) * range;
        double verticalRange = Math.sin(pitchRadians) * range;
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);

        cameraX = centerX - forwardX * horizontalRange;
        cameraY = centerY + verticalRange;
        cameraZ = centerZ - forwardZ * horizontalRange;
        cameraYaw = yawDegrees;
        cameraPitch = pitchDegrees;
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static double cameraX() {
        return cameraX;
    }

    public static double cameraY() {
        return cameraY;
    }

    public static double cameraZ() {
        return cameraZ;
    }

    public static float cameraYaw() {
        return cameraYaw;
    }

    public static float cameraPitch() {
        return cameraPitch;
    }

    public static float fov() {
        return fov;
    }

    public static void deactivate() {
        active = false;
    }
}
