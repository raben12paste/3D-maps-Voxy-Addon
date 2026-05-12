package com.voxymap.client.map;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class WaypointManager {
    private static final int[] COLORS = {
            0xFFFF4B6E, 0xFF45D6FF, 0xFFFFD166, 0xFF70E000, 0xFFB517FF
    };

    private final Path savePath = FabricLoader.getInstance().getConfigDir().resolve("voxymap-waypoints.txt");
    private final List<Waypoint> waypoints = new ArrayList<>();

    public WaypointManager() {
        load();
    }

    public List<Waypoint> all() {
        return Collections.unmodifiableList(waypoints);
    }

    public void add(String name, String dimension, double x, double y, double z) {
        int color = COLORS[Math.floorMod(waypoints.size(), COLORS.length)];
        waypoints.add(new Waypoint(name, dimension, x, y, z, color));
        save();
    }

    public void rename(int index, String name) {
        if (index < 0 || index >= waypoints.size()) return;
        Waypoint waypoint = waypoints.get(index);
        waypoints.set(index, new Waypoint(name, waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(), waypoint.color()));
        save();
    }

    public void remove(int index) {
        if (index < 0 || index >= waypoints.size()) return;
        waypoints.remove(index);
        save();
    }

    public int countInCurrentDimension(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) return 0;
        String dimension = minecraft.level.dimension().identifier().toString();
        int count = 0;
        for (Waypoint waypoint : waypoints) {
            if (waypoint.dimension().equals(dimension)) {
                count++;
            }
        }
        return count;
    }

    private void load() {
        waypoints.clear();
        if (!Files.isRegularFile(savePath)) return;

        try {
            for (String line : Files.readAllLines(savePath)) {
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6) continue;
                waypoints.add(new Waypoint(
                        unescape(parts[0]),
                        parts[1],
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]),
                        (int) Long.parseLong(parts[5], 16)
                ));
            }
        } catch (Exception ignored) {
            waypoints.clear();
        }
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            lines.add(String.join("|",
                    escape(waypoint.name()),
                    waypoint.dimension(),
                    format(waypoint.x()),
                    format(waypoint.y()),
                    format(waypoint.z()),
                    Integer.toHexString(waypoint.color())
            ));
        }

        try {
            Files.createDirectories(savePath.getParent());
            Files.write(savePath, lines);
        } catch (IOException ignored) {
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\p").replace("\n", " ");
    }

    private static String unescape(String value) {
        return value.replace("\\p", "|").replace("\\\\", "\\");
    }
}
