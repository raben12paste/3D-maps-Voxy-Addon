package com.voxymap.client.map;

/**
 * A snapshot of the map camera, in GUI coordinates: projects world points onto the screen
 * and casts screen points back onto a horizontal plane.
 *
 * <p>The basis mirrors vanilla {@code Camera.setRotation}, which rotates forward
 * {@code (0, 0, -1)}, up {@code (0, 1, 0)} and left {@code (-1, 0, 0)} by
 * {@code rotationYXZ(π - yaw, -pitch, 0)}.
 */
public record MapView(double cameraX, double cameraY, double cameraZ,
                      double forwardX, double forwardY, double forwardZ,
                      double leftX, double leftZ,
                      double upX, double upY, double upZ,
                      double tanHalfFovX, double tanHalfFovY,
                      double guiWidth, double guiHeight) {

    /** The camera {@link VoxyMapCameraController} currently holds, or {@code null} if the map is off. */
    public static MapView capture(double guiWidth, double guiHeight, double aspectRatio) {
        if (!VoxyMapCameraController.isActive() || guiWidth <= 0 || guiHeight <= 0) {
            return null;
        }

        double yaw = Math.toRadians(VoxyMapCameraController.cameraYaw());
        double pitch = Math.toRadians(VoxyMapCameraController.cameraPitch());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);
        double tanY = Math.tan(Math.toRadians(VoxyMapCameraController.fov()) * 0.5);
        return new MapView(
                VoxyMapCameraController.cameraX(), VoxyMapCameraController.cameraY(), VoxyMapCameraController.cameraZ(),
                -sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch,
                cosYaw, sinYaw,
                -sinYaw * sinPitch, cosPitch, cosYaw * sinPitch,
                tanY * aspectRatio, tanY,
                guiWidth, guiHeight);
    }

    /**
     * Where a world point lands on the screen. Points behind the camera come back with
     * {@code inFront == false} and their position mirrored, so the direction from the screen
     * centre still points the right way.
     */
    public ScreenPoint project(double x, double y, double z) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        double depth = dx * forwardX + dy * forwardY + dz * forwardZ;
        double left = dx * leftX + dz * leftZ;
        double up = dx * upX + dy * upY + dz * upZ;
        double safeDepth = Math.abs(depth) < 1.0E-6 ? 1.0E-6 : Math.abs(depth);
        double ndcX = left / (safeDepth * tanHalfFovX);
        double ndcY = up / (safeDepth * tanHalfFovY);
        return new ScreenPoint((1.0 - ndcX) * guiWidth * 0.5, (1.0 - ndcY) * guiHeight * 0.5, depth > 0.0);
    }

    /** Where the ray through a screen point meets the plane {@code y = planeY}, or {@code null}. */
    public GroundPoint groundPointAt(double guiX, double guiY, double planeY) {
        double ndcX = 1.0 - guiX / guiWidth * 2.0;
        double ndcY = 1.0 - guiY / guiHeight * 2.0;
        double rayX = forwardX + leftX * ndcX * tanHalfFovX + upX * ndcY * tanHalfFovY;
        double rayY = forwardY + upY * ndcY * tanHalfFovY;
        double rayZ = forwardZ + leftZ * ndcX * tanHalfFovX + upZ * ndcY * tanHalfFovY;
        if (Math.abs(rayY) < 1.0E-6) {
            return null;
        }

        double t = (planeY - cameraY) / rayY;
        if (t <= 0.0 || !Double.isFinite(t)) {
            return null;
        }
        return new GroundPoint(cameraX + rayX * t, cameraZ + rayZ * t);
    }

    /**
     * How far across the ground, from the camera, the screen reaches on the plane
     * {@code y = planeY}: the furthest of the four corners, or infinity when one of them looks
     * above the horizon. The ground the screen covers is a quadrilateral, so a corner is
     * always its furthest point.
     */
    public double groundReach(double planeY) {
        double reach = 0.0;
        for (int corner = 0; corner < 4; corner++) {
            GroundPoint point = groundPointAt((corner & 1) * guiWidth, (corner >> 1) * guiHeight, planeY);
            if (point == null) {
                return Double.POSITIVE_INFINITY;
            }
            reach = Math.max(reach, Math.hypot(point.x() - cameraX, point.z() - cameraZ));
        }
        return reach;
    }

    public record ScreenPoint(double x, double y, boolean inFront) {
    }

    public record GroundPoint(double x, double z) {
    }
}
