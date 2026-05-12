package com.voxymap.client.gui;

import com.voxymap.client.map.Waypoint;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class WaypointProjection {
    private WaypointProjection() {
    }

    public static ScreenPoint project(double pointX, double pointY, double pointZ, Camera camera, float fov, int width, int height) {
        Vec3 cameraPosition = camera.position();
        double dx = pointX - cameraPosition.x;
        double dy = pointY - cameraPosition.y;
        double dz = pointZ - cameraPosition.z;

        Vector3fc forwardVector = camera.forwardVector();
        Vector3fc upVector = camera.upVector();
        Vector3fc leftVector = camera.leftVector();
        double forward = dx * forwardVector.x() + dy * forwardVector.y() + dz * forwardVector.z();
        if (forward <= 0.5) {
            return null;
        }

        double left = dx * leftVector.x() + dy * leftVector.y() + dz * leftVector.z();
        double up = dx * upVector.x() + dy * upVector.y() + dz * upVector.z();
        double scale = (height * 0.5) / Math.tan(Math.toRadians(fov) * 0.5);
        double screenX = width * 0.5 - left * scale / forward;
        double screenY = height * 0.5 - up * scale / forward;
        if (screenX < -80 || screenX > width + 80 || screenY < -80 || screenY > height + 80) {
            return null;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new ScreenPoint((int) Math.round(screenX), (int) Math.round(screenY), distance);
    }

    public static void drawMarker(GuiGraphics g, Minecraft minecraft, Waypoint waypoint, ScreenPoint point) {
        int color = waypoint.color();
        int x = point.x();
        int y = point.y();

        g.fill(x - 2, y - 7, x + 3, y + 8, 0x66000000);
        g.fill(x - 7, y - 2, x + 8, y + 3, 0x66000000);
        g.fill(x - 1, y - 6, x + 2, y - 2, color);
        g.fill(x - 1, y + 3, x + 2, y + 7, color);
        g.fill(x - 6, y - 1, x - 2, y + 2, color);
        g.fill(x + 3, y - 1, x + 7, y + 2, color);
        g.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);

        String label = waypoint.name() + " " + (int) point.distance() + "m";
        int textX = Mth.clamp(x + 11, 4, g.guiWidth() - minecraft.font.width(label) - 4);
        int textY = Mth.clamp(y - 7, 4, g.guiHeight() - 12);
        g.fill(textX - 4, textY - 3, textX + minecraft.font.width(label) + 4, textY + 10, 0x99202838);
        g.fill(textX - 4, textY - 3, textX - 2, textY + 10, color);
        g.drawString(minecraft.font, label, textX, textY, 0xFFFFFFFF);
    }

    public record ScreenPoint(int x, int y, double distance) {
    }
}
