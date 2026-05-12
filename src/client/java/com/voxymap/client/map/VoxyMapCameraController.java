package com.voxymap.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

public final class VoxyMapCameraController {
    private static boolean active;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static float cameraYaw;
    private static float cameraPitch;
    private static float fov;

    private VoxyMapCameraController() {
    }

    public static void update(Minecraft minecraft, double centerX, double centerZ, double yawRadians, double pitchControl, double blocksPerPixel) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            active = false;
            return;
        }

        int surfaceY = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(centerX), Mth.floor(centerZ));
        double targetY = Math.max(minecraft.level.getMinY() + 8.0, surfaceY + 18.0);
        float pitchDegrees = Mth.clamp((float) (18.0 + pitchControl * 42.0), 18.0f, 72.0f);
        float yawDegrees = (float) Math.toDegrees(yawRadians);
        double desiredRange = Mth.clamp(blocksPerPixel * 210.0, 64.0, 8192.0);
        double range = Math.max(1024.0, desiredRange);
        fov = Mth.clamp((float) (70.0 * desiredRange / range), 6.0f, 70.0f);

        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontalRange = Math.cos(pitchRadians) * range;
        double verticalRange = Math.sin(pitchRadians) * range;
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);

        cameraX = centerX - forwardX * horizontalRange;
        cameraY = targetY + verticalRange;
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
