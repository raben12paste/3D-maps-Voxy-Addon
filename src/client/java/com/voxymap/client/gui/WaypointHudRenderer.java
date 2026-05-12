package com.voxymap.client.gui;

import com.voxymap.client.VoxyMapClient;
import com.voxymap.client.map.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class WaypointHudRenderer {
    private WaypointHudRenderer() {
    }

    public static void render(GuiGraphics g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        if (minecraft.screen instanceof MapScreen) return;
        if (VoxyMapClient.waypointManager == null) return;

        String dimension = minecraft.level.dimension().identifier().toString();
        int drawn = 0;
        for (Waypoint waypoint : VoxyMapClient.waypointManager.all()) {
            if (!waypoint.dimension().equals(dimension)) continue;
            drawWaypoint(g, minecraft, waypoint, drawn++);
            if (drawn >= 8) break;
        }
    }

    private static void drawWaypoint(GuiGraphics g, Minecraft minecraft, Waypoint waypoint, int row) {
        if (minecraft.gameRenderer == null) return;
        WaypointProjection.ScreenPoint point = WaypointProjection.project(
                waypoint.x(), waypoint.y() + 2.0, waypoint.z(),
                minecraft.gameRenderer.getMainCamera(), 70.0f, g.guiWidth(), g.guiHeight()
        );
        if (point != null) {
            WaypointProjection.drawMarker(g, minecraft, waypoint, point);
        }
    }
}
