package com.voxymap.client.map;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides top-down map colors for blocks.
 * Uses Minecraft's built-in MapColor system where possible,
 * with manual overrides for common blocks.
 */
public class BlockColorTable {

    // Cache for block → ARGB color
    private static final Map<Block, Integer> COLOR_CACHE = new HashMap<>(512);

    /**
     * Get ARGB color for a given BlockState for top-down map rendering.
     * Returns 0 (transparent) for air/void.
     */
    public static int getMapColor(BlockState state) {
        if (state == null || state.isAir()) return 0x00000000;

        Block block = state.getBlock();
        return COLOR_CACHE.computeIfAbsent(block, BlockColorTable::computeColor);
    }

    private static int computeColor(Block block) {
        BlockState defaultState = block.defaultBlockState();

        // Use Minecraft's built-in MapColor
        MapColor mapColor = defaultState.getMapColor(null, null);
        if (mapColor != null && mapColor != MapColor.NONE) {
            int rgb = mapColor.col;
            // Convert RGB to ARGB with full opacity
            return 0xFF000000 | rgb;
        }

        // Fallback: air/unknown = transparent
        return 0x00000000;
    }

    /**
     * Apply height shading to a color.
     * Higher blocks = lighter, lower blocks = darker.
     * @param argb base ARGB color
     * @param height world Y coordinate (-64 to 320)
     * @param referenceHeight reference Y for neutral shade (sea level = 63)
     */
    public static int applyHeightShading(int argb, int height, int referenceHeight) {
        if ((argb & 0xFF000000) == 0) return argb; // transparent stays transparent

        int diff = height - referenceHeight;
        // Clamp shade between -0.3 and +0.3
        float shade = Math.max(-0.3f, Math.min(0.3f, diff / 50.0f));

        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >> 24) & 0xFF;

        r = Math.max(0, Math.min(255, (int)(r * (1.0f + shade))));
        g = Math.max(0, Math.min(255, (int)(g * (1.0f + shade))));
        b = Math.max(0, Math.min(255, (int)(b * (1.0f + shade))));

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Blend two ARGB colors together. ratio=0.0 = full colorA, ratio=1.0 = full colorB
     */
    public static int blendColors(int colorA, int colorB, float ratio) {
        int rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int r = (int)(rA + (rB - rA) * ratio);
        int g = (int)(gA + (gB - gA) * ratio);
        int b = (int)(bA + (bB - bA) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
