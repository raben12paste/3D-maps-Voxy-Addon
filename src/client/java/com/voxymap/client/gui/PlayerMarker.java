package com.voxymap.client.gui;

import com.voxymap.client.map.MapView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Marks the player on the map: an arrow turned to where the player faces, on a disc with a
 * pulsing ring. When the player is off screen the marker sits on the map edge
 * with a pointer towards them and the distance from the map centre, and clicking it brings
 * the map back to the player.
 */
final class PlayerMarker {
    private static final float ARROW_LENGTH = 13.0f;
    private static final float ARROW_WIDTH = 11.0f;
    private static final float EDGE_ARROW_SCALE = 0.75f;
    private static final float DISC_RADIUS = 9.5f;
    private static final float PULSE_MIN_RADIUS = 9.0f;
    private static final float PULSE_MAX_RADIUS = 22.0f;
    private static final long PULSE_PERIOD_NANOS = 1_800_000_000L;
    private static final int ON_SCREEN_MARGIN = 6;
    private static final int CLICK_RADIUS = 12;

    private static final int DISC_COLOR = 0xC0030914;
    private static final int ARROW_COLOR = 0xFFFFFFFF;
    private static final int RING_COLOR = 0xFF3BA4FF;
    private static final int PULSE_COLOR = 0x7DEBFF;
    private static final int LABEL_COLOR = 0xFFDFF8FF;

    private boolean edgeMarkerShown;
    private double edgeMarkerX;
    private double edgeMarkerY;

    /**
     * @param ground     the point under the player the marker sits on
     * @param facingYaw  the player's yaw in degrees
     * @param centre     the map focus, for the off-screen pointer and distance
     * @param step       a short ground distance in blocks, a few pixels long on screen
     * @param safeLeft   the area the off-screen marker is kept inside, clear of the HUD
     */
    void draw(GuiGraphicsExtractor g, Minecraft minecraft, MapView view,
              double groundX, double groundY, double groundZ, float facingYaw,
              double centreX, double centreY, double centreZ, double step,
              int safeLeft, int safeTop, int safeRight, int safeBottom) {
        edgeMarkerShown = false;
        if (view == null) {
            return;
        }

        double yaw = Math.toRadians(facingYaw);
        double facingX = -Math.sin(yaw);
        double facingZ = Math.cos(yaw);
        int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());

        MapView.ScreenPoint player = view.project(groundX, groundY, groundZ);
        boolean onScreen = player.inFront()
                && player.x() >= ON_SCREEN_MARGIN && player.x() <= view.guiWidth() - ON_SCREEN_MARGIN
                && player.y() >= ON_SCREEN_MARGIN && player.y() <= view.guiHeight() - ON_SCREEN_MARGIN;

        if (onScreen) {
            MapView.ScreenPoint ahead = view.project(groundX + facingX * step, groundY, groundZ + facingZ * step);
            float facing = screenAngle(player, ahead);
            float x = (float) player.x();
            float y = (float) player.y();
            drawPulse(g, x, y, guiScale);
            drawDisc(g, x, y, DISC_RADIUS, guiScale);
            drawArrow(g, x, y, facing, 1.0f, guiScale);
            return;
        }

        // Off screen: walk from the screen centre towards the player until the safe area ends.
        MapView.ScreenPoint centre = view.project(centreX, centreY, centreZ);
        MapView.ScreenPoint centreAhead = view.project(centreX + facingX * step, centreY, centreZ + facingZ * step);
        double originX = view.guiWidth() * 0.5;
        double originY = view.guiHeight() * 0.5;
        double dirX = player.x() - originX;
        double dirY = player.y() - originY;
        double length = Math.hypot(dirX, dirY);
        if (length < 1.0E-6) {
            return;
        }
        dirX /= length;
        dirY /= length;
        double reach = Double.MAX_VALUE;
        if (dirX > 1.0E-6) reach = Math.min(reach, (safeRight - originX) / dirX);
        if (dirX < -1.0E-6) reach = Math.min(reach, (safeLeft - originX) / dirX);
        if (dirY > 1.0E-6) reach = Math.min(reach, (safeBottom - originY) / dirY);
        if (dirY < -1.0E-6) reach = Math.min(reach, (safeTop - originY) / dirY);
        if (!Double.isFinite(reach) || reach <= 0.0) {
            return;
        }

        float x = (float) (originX + dirX * reach);
        float y = (float) (originY + dirY * reach);
        float pointer = (float) Math.atan2(dirX, -dirY);
        drawPointer(g, x, y, pointer, guiScale);
        drawDisc(g, x, y, DISC_RADIUS, guiScale);
        drawArrow(g, x, y, screenAngle(centre, centreAhead), EDGE_ARROW_SCALE, guiScale);

        int distance = (int) Math.round(Math.hypot(groundX - centreX, groundZ - centreZ));
        String label = Component.translatable("overlay.voxymap.player_distance", distance).getString();
        int labelWidth = minecraft.font.width(label);
        int labelX = Mth.clamp(Math.round(x) - labelWidth / 2, safeLeft - 8, safeRight + 8 - labelWidth);
        int labelY = y > originY ? Math.round(y - DISC_RADIUS) - 13 : Math.round(y + DISC_RADIUS) + 4;
        g.fill(labelX - 3, labelY - 2, labelX + labelWidth + 3, labelY + 10, DISC_COLOR);
        g.text(minecraft.font, label, labelX, labelY, LABEL_COLOR);

        edgeMarkerShown = true;
        edgeMarkerX = x;
        edgeMarkerY = y;
    }

    /** Whether a click lands on the off-screen marker. */
    boolean edgeMarkerContains(double mouseX, double mouseY) {
        return edgeMarkerShown && Math.hypot(mouseX - edgeMarkerX, mouseY - edgeMarkerY) <= CLICK_RADIUS;
    }

    /** Clockwise angle from screen-up of the line from {@code from} to {@code to}. */
    private static float screenAngle(MapView.ScreenPoint from, MapView.ScreenPoint to) {
        return (float) Math.atan2(to.x() - from.x(), -(to.y() - from.y()));
    }

    /** A notched arrowhead pointing along {@code angle}, drawn row by row at screen resolution. */
    private static void drawArrow(GuiGraphicsExtractor g, float x, float y, float angle, float scale, int guiScale) {
        withScreenPixels(g, x, y, guiScale, () -> {
            g.pose().rotate(angle);
            float length = ARROW_LENGTH * scale * guiScale;
            float halfWidth = ARROW_WIDTH * 0.5f * scale * guiScale;
            float tipY = -length * 0.5f;
            float baseY = length * 0.5f;
            float notchY = baseY - length * 0.3f;
            for (int row = Math.round(tipY); row < Math.round(baseY); row++) {
                float centreY = row + 0.5f;
                int outer = Math.round((centreY - tipY) / length * halfWidth);
                if (outer <= 0) {
                    continue;
                }
                if (centreY <= notchY) {
                    g.fill(-outer, row, outer, row + 1, ARROW_COLOR);
                } else {
                    int inner = Math.round((centreY - notchY) / (baseY - notchY) * halfWidth);
                    if (outer > inner) {
                        g.fill(-outer, row, -inner, row + 1, ARROW_COLOR);
                        g.fill(inner, row, outer, row + 1, ARROW_COLOR);
                    }
                }
            }
        });
    }

    private static void drawDisc(GuiGraphicsExtractor g, float x, float y, float radius, int guiScale) {
        withScreenPixels(g, x, y, guiScale, () -> {
            float r = radius * guiScale;
            fillCircle(g, r, 0.0f, DISC_COLOR);
            fillCircle(g, r, r - guiScale, RING_COLOR);
        });
    }

    /** A ring that grows and fades out, so the marker is easy to spot on busy terrain. */
    private static void drawPulse(GuiGraphicsExtractor g, float x, float y, int guiScale) {
        float phase = (System.nanoTime() % PULSE_PERIOD_NANOS) / (float) PULSE_PERIOD_NANOS;
        float radius = Mth.lerp(phase, PULSE_MIN_RADIUS, PULSE_MAX_RADIUS) * guiScale;
        int alpha = Math.round((1.0f - phase) * 0xB0);
        if (alpha <= 0) {
            return;
        }
        withScreenPixels(g, x, y, guiScale, () -> fillCircle(g, radius, radius - guiScale, (alpha << 24) | PULSE_COLOR));
    }

    /** A small triangle on the edge of the disc pointing towards the off-screen player. */
    private static void drawPointer(GuiGraphicsExtractor g, float x, float y, float angle, int guiScale) {
        withScreenPixels(g, x, y, guiScale, () -> {
            g.pose().rotate(angle);
            int base = Math.round(DISC_RADIUS * guiScale) - guiScale;
            int height = 6 * guiScale;
            int halfWidth = 5 * guiScale;
            for (int row = 0; row < height; row++) {
                int half = Math.max(1, Math.round(halfWidth * (row + 1) / (float) height));
                int rowY = -base - height + row;
                g.fill(-half, rowY, half, rowY + 1, RING_COLOR);
            }
        });
    }

    /** Runs {@code draw} centred on {@code (x, y)} with one unit per real screen pixel. */
    private static void withScreenPixels(GuiGraphicsExtractor g, float x, float y, int guiScale, Runnable draw) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.0f / guiScale, 1.0f / guiScale);
        draw.run();
        g.pose().popMatrix();
    }

    /** Fills the ring between {@code inner} and {@code outer} (a disc when {@code inner} is 0), row by row. */
    private static void fillCircle(GuiGraphicsExtractor g, float outer, float inner, int color) {
        int rows = (int) Math.ceil(outer);
        for (int row = -rows; row < rows; row++) {
            float centreY = row + 0.5f;
            float outerHalf = halfChord(outer, centreY);
            if (outerHalf <= 0.0f) {
                continue;
            }
            float innerHalf = inner > 0.0f ? halfChord(inner, centreY) : 0.0f;
            int outerPx = Math.round(outerHalf);
            int innerPx = Math.round(innerHalf);
            if (innerPx <= 0) {
                g.fill(-outerPx, row, outerPx, row + 1, color);
            } else if (outerPx > innerPx) {
                g.fill(-outerPx, row, -innerPx, row + 1, color);
                g.fill(innerPx, row, outerPx, row + 1, color);
            }
        }
    }

    private static float halfChord(float radius, float y) {
        float squared = radius * radius - y * y;
        return squared > 0.0f ? (float) Math.sqrt(squared) : 0.0f;
    }
}
