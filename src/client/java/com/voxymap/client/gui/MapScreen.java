package com.voxymap.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxymap.client.VoxyMapClient;
import com.voxymap.client.map.MapDataManager;
import com.voxymap.client.map.BlockColorTable;
import com.voxymap.client.map.VoxyBridge;
import com.voxymap.client.map.VoxyMapCameraController;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Full-screen world map screen opened by the configured keybinding (default: M).
 *
 * Controls:
 *   LMB drag   - pan the view
 *   Scroll     - zoom in/out
 *   WASD       - pan with keyboard
 *   + / -      - zoom keyboard
 *   C          - center on player
 *   ESC / M    - close
 */
public class MapScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.voxymap.title");

    // Texture rendered every frame from the pixel cache
    private DynamicTexture mapTexture;
    private Identifier mapTextureId;
    private static final int TEXTURE_SIZE = 512;
    private static final int BOTTOM_BAR_HEIGHT = 48;
    private static final int MAX_DRAWN_TILES = 12000;
    private static final int MAX_DETAIL_COLUMNS = 6000;
    private static final double MAX_DETAIL_BLOCKS_PER_PIXEL = 6.0;
    private static final double BASE_HEIGHT = 63.0;
    private static final boolean ALLOW_UNSAFE_NATIVE_VOXY_RENDERER = false;
    private static final long OPEN_ANIMATION_NANOS = 850_000_000L;
    private static final long END_DEBUG_LOG_INTERVAL_MS = 3000L;
    private static boolean warnedUntestedVoxyVersion = false;

    // View state
    private double viewCenterX;
    private double viewCenterZ;
    private double blocksPerPixel = 4.0;
    private double viewYaw = Math.toRadians(45.0);
    private double viewPitch = 0.68;
    private double heightScale = 0.65;
    private boolean useNativeVoxyRenderer = false;
    private boolean nativeRendererFailed = false;
    private long lastNativeRendererWarning = 0L;
    private long lastEndDebugLog = 0L;

    // Drag state
    private boolean dragging = false;
    private boolean rotating = false;
    private double dragStartMouseX, dragStartMouseY;
    private double dragStartViewX, dragStartViewZ;
    private double dragStartYaw, dragStartPitch;
    private double moveVelocityX = 0.0;
    private double moveVelocityZ = 0.0;
    private double yawVelocity = 0.0;
    private double pitchVelocity = 0.0;
    private long lastFrameNanos = 0L;
    private long openedAtNanos = 0L;

    private final MapDataManager data;

    // UI colors
    private static final int BG          = 0xFF080810;
    private static final int PANEL_BG    = 0xCC000016;
    private static final int BORDER      = 0xFF1A3A6A;
    private static final int NOT_SCANNED = 0xFF0D0D1E;

    public MapScreen() {
        super(TITLE);
        this.data = VoxyMapClient.mapDataManager;
    }

    @Override
    protected void init() {
        super.init();
        openedAtNanos = System.nanoTime();
        lastFrameNanos = 0L;
        if (minecraft.player != null) {
            viewCenterX = minecraft.player.getX();
            viewCenterZ = minecraft.player.getZ();
        }
        if (!isUnsupportedDimension()) {
            VoxyBridge.suppressEnvironmentalFogForMap();
            syncWorldCamera();
        } else {
            VoxyMapCameraController.deactivate();
        }
        createTexture();
        if (!VoxyBridge.isVoxyPresent() && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable("message.voxymap.voxy_missing"), false);
        } else if (!VoxyBridge.isTestedVoxyVersion() && !warnedUntestedVoxyVersion) {
            VoxyMapClient.LOGGER.warn("[VoxyMap] You are using Voxy {}, which has not been tested with VoxyMap. Visual glitches or crashes may occur. Tested Voxy versions: {}.",
                    VoxyBridge.getVoxyVersion(), VoxyBridge.testedVoxyVersionsText());
            warnedUntestedVoxyVersion = true;
        }
    }

    private void createTexture() {
        releaseTexture();
        mapTexture = new DynamicTexture("voxymap_frame", TEXTURE_SIZE, TEXTURE_SIZE, false);
        mapTextureId = Identifier.withDefaultNamespace("voxymap/map_frame");
        minecraft.getTextureManager().register(mapTextureId, mapTexture);
    }

    private void releaseTexture() {
        if (mapTextureId != null && mapTexture != null) {
            minecraft.getTextureManager().release(mapTextureId);
            mapTextureId = null;
        }
        if (mapTexture != null) {
            mapTexture.close();
            mapTexture = null;
        }
    }

    // ======================= Rendering =======================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (isUnsupportedDimension()) {
            VoxyMapCameraController.deactivate();
            drawUnsupportedDimension(g);
            drawOpeningAnimation(g);
            super.render(g, mouseX, mouseY, delta);
            return;
        }

        updateSmoothControls();
        VoxyBridge.suppressEnvironmentalFogForMap();
        syncWorldCamera();
        if (isEndDimension()) {
            if (VoxyMapClient.debugLogging && VoxyMapClient.mapDataManager != null) {
                VoxyMapClient.mapDataManager.tick(minecraft);
            }
            logEndRenderDebug();
        }
        drawWorldViewerOverlay(g);
        drawOpeningAnimation(g);
        super.render(g, mouseX, mouseY, delta);
    }

    private double openingProgress() {
        if (openedAtNanos == 0L) return 1.0;
        double raw = (System.nanoTime() - openedAtNanos) / (double) OPEN_ANIMATION_NANOS;
        raw = Mth.clamp(raw, 0.0, 1.0);
        return 1.0 - Math.pow(1.0 - raw, 3.0);
    }

    private void updateSmoothControls() {
        if (minecraft == null || minecraft.getWindow() == null) return;

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 1.0 / 60.0 : Math.min(0.08, (now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;

        long window = minecraft.getWindow().handle();
        double forwardInput = keyDown(window, GLFW.GLFW_KEY_W) ? 1.0 : 0.0;
        forwardInput -= keyDown(window, GLFW.GLFW_KEY_S) ? 1.0 : 0.0;
        double strafeInput = keyDown(window, GLFW.GLFW_KEY_A) ? 1.0 : 0.0;
        strafeInput -= keyDown(window, GLFW.GLFW_KEY_D) ? 1.0 : 0.0;
        double turnInput = keyDown(window, GLFW.GLFW_KEY_E) ? 1.0 : 0.0;
        turnInput -= keyDown(window, GLFW.GLFW_KEY_Q) ? 1.0 : 0.0;
        double pitchInput = keyDown(window, GLFW.GLFW_KEY_PAGE_DOWN) ? 1.0 : 0.0;
        pitchInput -= keyDown(window, GLFW.GLFW_KEY_PAGE_UP) ? 1.0 : 0.0;

        double speedBoost = keyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || keyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 2.5 : 1.0;
        double moveSpeed = Math.max(12.0, blocksPerPixel * 76.0) * speedBoost;
        double forwardX = -Math.sin(viewYaw);
        double forwardZ = Math.cos(viewYaw);
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double targetVX = (forwardX * forwardInput + rightX * strafeInput) * moveSpeed;
        double targetVZ = (forwardZ * forwardInput + rightZ * strafeInput) * moveSpeed;

        double response = 1.0 - Math.exp(-dt * 10.0);
        moveVelocityX = Mth.lerp(response, moveVelocityX, targetVX);
        moveVelocityZ = Mth.lerp(response, moveVelocityZ, targetVZ);
        yawVelocity = Mth.lerp(response, yawVelocity, turnInput * 1.65);
        pitchVelocity = Mth.lerp(response, pitchVelocity, pitchInput * 0.72);

        if (!dragging) {
            viewCenterX += moveVelocityX * dt;
            viewCenterZ += moveVelocityZ * dt;
        }
        if (!rotating) {
            viewYaw += yawVelocity * dt;
            viewPitch = Mth.clamp(viewPitch + pitchVelocity * dt, 0.2, 1.1);
        }
    }

    private static boolean keyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
    }

    private void syncWorldCamera() {
        if (isUnsupportedDimension()) {
            VoxyMapCameraController.deactivate();
            return;
        }
        VoxyMapCameraController.update(minecraft, viewCenterX, viewCenterZ, viewYaw, viewPitch, blocksPerPixel);
    }

    private boolean isUnsupportedDimension() {
        if (minecraft == null || minecraft.level == null) return false;
        String dimension = minecraft.level.dimension().identifier().toString();
        return "minecraft:the_nether".equals(dimension);
    }

    private boolean isEndDimension() {
        return minecraft != null && minecraft.level != null
                && "minecraft:the_end".equals(minecraft.level.dimension().identifier().toString());
    }

    private void drawUnsupportedDimension(GuiGraphics g) {
        g.fill(0, 0, width, height, BG);
        int boxW = Math.min(width - 40, 420);
        int boxH = 112;
        int x = (width - boxW) / 2;
        int y = (height - boxH) / 2;
        g.fill(x - 2, y - 2, x + boxW + 2, y + boxH + 2, 0x553BA4FF);
        g.fill(x, y, x + boxW, y + boxH, 0xEE030914);
        g.fill(x, y, x + 4, y + boxH, 0xFF3BA4FF);
        g.drawCenteredString(minecraft.font, Component.translatable("screen.voxymap.unsupported.title"), width / 2, y + 22, 0xFFFFFFFF);
        g.drawCenteredString(minecraft.font, Component.translatable(unsupportedDimensionMessageKey()), width / 2, y + 46, 0xFFBFD8FF);
        g.drawCenteredString(minecraft.font, Component.translatable("screen.voxymap.unsupported.close"), width / 2, y + 74, 0xFF8EE6FF);
    }

    private String unsupportedDimensionMessageKey() {
        if (minecraft != null && minecraft.level != null
                && "minecraft:the_end".equals(minecraft.level.dimension().identifier().toString())) {
            return "screen.voxymap.unsupported.end";
        }
        return "screen.voxymap.unsupported.nether";
    }

    private void drawOpeningAnimation(GuiGraphics g) {
        double progress = openingProgress();
        if (progress >= 0.995) return;

        int alpha = (int) (185 * (1.0 - progress));
        int dim = (alpha << 24) | 0x030914;
        g.fill(0, 0, width, height, dim);

        int bandHeight = (int) ((height * 0.28) * (1.0 - progress));
        if (bandHeight > 0) {
            g.fill(0, 0, width, bandHeight, 0xDD030914);
            g.fill(0, height - bandHeight, width, height, 0xDD030914);
            g.fill(0, bandHeight, width, bandHeight + 2, 0xFF3BA4FF);
            g.fill(0, height - bandHeight - 2, width, height - bandHeight, 0xFF3BA4FF);
        }

        int scanY = (int) (height * progress);
        g.fill(0, scanY - 1, width, scanY + 1, 0xAA72D7FF);
        String title = "VOXYMAP";
        int titleAlpha = (int) (255 * (1.0 - Math.abs(progress - 0.35) / 0.35));
        if (titleAlpha > 0) {
            int color = (Mth.clamp(titleAlpha, 0, 255) << 24) | 0xDFF8FF;
            g.drawCenteredString(minecraft.font, title, width / 2, height / 2 - 5, color);
        }
    }

    private void logEndRenderDebug() {
        if (!VoxyMapClient.debugLogging || minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEndDebugLog < END_DEBUG_LOG_INTERVAL_MS) {
            return;
        }
        lastEndDebugLog = now;

        int camChunkX = Mth.floor(VoxyMapCameraController.cameraX()) >> 4;
        int camChunkZ = Mth.floor(VoxyMapCameraController.cameraZ()) >> 4;
        int centerChunkX = Mth.floor(viewCenterX) >> 4;
        int centerChunkZ = Mth.floor(viewCenterZ) >> 4;
        int loadedAroundCenter = countLoadedChunks(centerChunkX, centerChunkZ, 6);
        int loadedAroundCamera = countLoadedChunks(camChunkX, camChunkZ, 6);
        VoxyMapClient.LOGGER.info("[VoxyMap][EndDiag] render active={} dimension={} player=({}, {}, {}) center=({}, {}) camera=({}, {}, {}) yaw={} pitch={} fov={} zoom={} window={}x{} gui={}x{} centerChunkLoaded={} cameraChunkLoaded={} loadedChunks13x13(center/camera)={}/{} skyDarken={} minY={} maxY={} data={}",
                VoxyMapCameraController.isActive(),
                minecraft.level.dimension().identifier(),
                (int) minecraft.player.getX(), (int) minecraft.player.getY(), (int) minecraft.player.getZ(),
                (int) viewCenterX, (int) viewCenterZ,
                (int) VoxyMapCameraController.cameraX(), (int) VoxyMapCameraController.cameraY(), (int) VoxyMapCameraController.cameraZ(),
                VoxyMapCameraController.cameraYaw(), VoxyMapCameraController.cameraPitch(), VoxyMapCameraController.fov(), blocksPerPixel,
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), width, height,
                minecraft.level.hasChunk(centerChunkX, centerChunkZ),
                minecraft.level.hasChunk(camChunkX, camChunkZ),
                loadedAroundCenter,
                loadedAroundCamera,
                minecraft.level.getSkyDarken(),
                minecraft.level.getMinY(),
                minecraft.level.getMaxY(),
                data == null ? "null" : data.getDebugStatus());
        VoxyMapClient.LOGGER.info("[VoxyMap][EndDiag] voxy {}", VoxyBridge.describeDiagnostics(minecraft));
        VoxyMapClient.LOGGER.info("[VoxyMap][EndDiag] samples center={} player={} camera={}",
                data == null ? "null" : data.describeAreaDiagnostics(minecraft, (int) viewCenterX, (int) viewCenterZ, 2),
                data == null ? "null" : data.describeAreaDiagnostics(minecraft, (int) minecraft.player.getX(), (int) minecraft.player.getZ(), 2),
                data == null ? "null" : data.describeAreaDiagnostics(minecraft, Mth.floor(VoxyMapCameraController.cameraX()), Mth.floor(VoxyMapCameraController.cameraZ()), 1));
    }

    private int countLoadedChunks(int centerChunkX, int centerChunkZ, int radius) {
        if (minecraft == null || minecraft.level == null) return 0;
        int loaded = 0;
        for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
                if (minecraft.level.hasChunk(x, z)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    private void drawTerrain3d(GuiGraphics g, int mapLeft, int mapTop, int mapDrawSize) {
        if (data == null) return;

        int blocksPerTile = data.getBlocksPerTile();
        double halfViewBlocks = mapDrawSize * blocksPerPixel / 2.0;
        int minTileX = data.blockToTile((int) Math.floor(viewCenterX - halfViewBlocks));
        int maxTileX = data.blockToTile((int) Math.ceil(viewCenterX + halfViewBlocks));
        int minTileZ = data.blockToTile((int) Math.floor(viewCenterZ - halfViewBlocks));
        int maxTileZ = data.blockToTile((int) Math.ceil(viewCenterZ + halfViewBlocks));

        int tilesX = Math.max(1, maxTileX - minTileX + 1);
        int tilesZ = Math.max(1, maxTileZ - minTileZ + 1);
        int stride = (int) Math.ceil(Math.sqrt((tilesX * (double) tilesZ) / MAX_DRAWN_TILES));
        stride = Math.max(1, stride);
        boolean allowVanillaFallback = tilesX * (double) tilesZ <= 4096;
        double cos = Math.cos(viewYaw);
        double sin = Math.sin(viewYaw);
        double centerX = mapLeft + mapDrawSize / 2.0;
        double centerY = mapTop + mapDrawSize * 0.58;
        double tileScreenSize = Math.max(2.0, blocksPerTile * stride / blocksPerPixel);
        List<TerrainTile> tiles = new ArrayList<>(Math.min(MAX_DRAWN_TILES, tilesX * tilesZ));

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ += stride) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX += stride) {
                int color = data.getColorForTile(minecraft, tileX, tileZ, allowVanillaFallback);
                if (color == 0) continue;

                double blockX = (tileX + stride * 0.5) * blocksPerTile;
                double blockZ = (tileZ + stride * 0.5) * blocksPerTile;
                double relX = blockX - viewCenterX;
                double relZ = blockZ - viewCenterZ;
                double rotatedX = relX * cos - relZ * sin;
                double rotatedZ = relX * sin + relZ * cos;
                int height = data.getHeightForTile(tileX, tileZ);
                double heightPixels = (height - BASE_HEIGHT) * heightScale / Math.sqrt(Math.max(0.5, blocksPerPixel));
                double screenX = centerX + rotatedX / blocksPerPixel;
                double screenY = centerY + rotatedZ * viewPitch / blocksPerPixel - heightPixels;

                int size = (int) Math.max(2, Math.ceil(tileScreenSize));
                if (screenX + size < mapLeft || screenX - size > mapLeft + mapDrawSize ||
                        screenY + size < mapTop || screenY - size > mapTop + mapDrawSize) {
                    continue;
                }
                tiles.add(new TerrainTile(screenX, screenY, rotatedZ, size, heightPixels,
                        shadeByDepth(color, rotatedZ, halfViewBlocks)));
            }
        }

        tiles.sort(Comparator.comparingDouble(t -> t.depth));
        for (TerrainTile tile : tiles) {
            int x0 = Mth.clamp((int) Math.floor(tile.x - tile.size / 2.0), mapLeft, mapLeft + mapDrawSize);
            int y0 = Mth.clamp((int) Math.floor(tile.y - tile.size / 2.0), mapTop, mapTop + mapDrawSize);
            int x1 = Mth.clamp((int) Math.ceil(tile.x + tile.size / 2.0), mapLeft, mapLeft + mapDrawSize);
            int y1 = Mth.clamp((int) Math.ceil(tile.y + tile.size / 2.0), mapTop, mapTop + mapDrawSize);
            if (x0 >= x1 || y0 >= y1) continue;

            int baseY = Mth.clamp((int) Math.ceil(tile.y + Math.max(4, Math.abs(tile.heightPixels) * 0.55)),
                    mapTop, mapTop + mapDrawSize);
            if (baseY > y1) {
                g.fill(x0, y1, x1, baseY, darken(tile.color, 0.62f));
            }
            g.fill(x0, y0, x1, y1, tile.color);
            if (tile.size >= 6) {
                g.fill(x0, y0, x1, y0 + 1, lighten(tile.color, 1.12f));
            }
        }
    }

    private int shadeByDepth(int color, double depth, double halfViewBlocks) {
        double normalized = Mth.clamp(depth / Math.max(1.0, halfViewBlocks), -1.0, 1.0);
        return lighten(color, (float) (1.0 - normalized * 0.18));
    }

    private int darken(int color, float factor) {
        return lighten(color, factor);
    }

    private int lighten(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = Mth.clamp((int) (((color >> 16) & 0xFF) * factor), 0, 255);
        int g = Mth.clamp((int) (((color >> 8) & 0xFF) * factor), 0, 255);
        int b = Mth.clamp((int) ((color & 0xFF) * factor), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawLoadedBlockDetail(GuiGraphics g, int mapLeft, int mapTop, int mapDrawSize) {
        if (minecraft == null || minecraft.level == null) return;
        if (blocksPerPixel > MAX_DETAIL_BLOCKS_PER_PIXEL) return;

        double halfViewBlocks = mapDrawSize * blocksPerPixel / 2.0;
        int minX = (int) Math.floor(viewCenterX - halfViewBlocks);
        int maxX = (int) Math.ceil(viewCenterX + halfViewBlocks);
        int minZ = (int) Math.floor(viewCenterZ - halfViewBlocks);
        int maxZ = (int) Math.ceil(viewCenterZ + halfViewBlocks);
        int columnsX = Math.max(1, maxX - minX + 1);
        int columnsZ = Math.max(1, maxZ - minZ + 1);
        int step = (int) Math.ceil(Math.sqrt((columnsX * (double) columnsZ) / MAX_DETAIL_COLUMNS));
        step = Math.max(1, step);

        double cos = Math.cos(viewYaw);
        double sin = Math.sin(viewYaw);
        double centerX = mapLeft + mapDrawSize / 2.0;
        double centerY = mapTop + mapDrawSize * 0.58;
        double blockScreenSize = Math.max(1.0, step / blocksPerPixel);
        List<TerrainTile> blocks = new ArrayList<>(Math.min(MAX_DETAIL_COLUMNS, columnsX * columnsZ));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int minY = minecraft.level.getMinY();
        int maxY = minecraft.level.getMaxY();
        for (int z = minZ; z <= maxZ; z += step) {
            for (int x = minX; x <= maxX; x += step) {
                int y = findSurfaceY(pos, x, z, minY, maxY);
                if (y == Integer.MIN_VALUE) continue;

                pos.set(x, y, z);
                int color = BlockColorTable.getMapColor(minecraft.level.getBlockState(pos));
                if ((color & 0xFF000000) == 0) continue;
                color = BlockColorTable.applyHeightShading(color, y, 63);

                double relX = x - viewCenterX;
                double relZ = z - viewCenterZ;
                double rotatedX = relX * cos - relZ * sin;
                double rotatedZ = relX * sin + relZ * cos;
                double heightPixels = (y - BASE_HEIGHT) * heightScale / Math.sqrt(Math.max(0.5, blocksPerPixel));
                double screenX = centerX + rotatedX / blocksPerPixel;
                double screenY = centerY + rotatedZ * viewPitch / blocksPerPixel - heightPixels;

                int size = (int) Math.max(1, Math.ceil(blockScreenSize));
                if (screenX + size < mapLeft || screenX - size > mapLeft + mapDrawSize ||
                        screenY + size < mapTop || screenY - size > mapTop + mapDrawSize) {
                    continue;
                }
                blocks.add(new TerrainTile(screenX, screenY, rotatedZ, size, heightPixels,
                        lighten(color, step == 1 ? 1.18f : 1.05f)));
            }
        }

        blocks.sort(Comparator.comparingDouble(t -> t.depth));
        for (TerrainTile block : blocks) {
            int x0 = Mth.clamp((int) Math.floor(block.x - block.size / 2.0), mapLeft, mapLeft + mapDrawSize);
            int y0 = Mth.clamp((int) Math.floor(block.y - block.size / 2.0), mapTop, mapTop + mapDrawSize);
            int x1 = Mth.clamp((int) Math.ceil(block.x + block.size / 2.0), mapLeft, mapLeft + mapDrawSize);
            int y1 = Mth.clamp((int) Math.ceil(block.y + block.size / 2.0), mapTop, mapTop + mapDrawSize);
            if (x0 >= x1 || y0 >= y1) continue;
            g.fill(x0, y0, x1, y1, block.color);
        }
    }

    private int findSurfaceY(BlockPos.MutableBlockPos pos, int x, int z, int minY, int maxY) {
        for (int y = maxY - 1; y >= minY; y--) {
            pos.set(x, y, z);
            var state = minecraft.level.getBlockState(pos);
            if (state != null && !state.isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private void updateTexture() {
        if (mapTexture == null || data == null) return;
        NativeImage img = mapTexture.getPixels();
        if (img == null) return;

        for (int py = 0; py < TEXTURE_SIZE; py++) {
            for (int px = 0; px < TEXTURE_SIZE; px++) {
                int blockX = (int) Math.floor(viewCenterX + (px - TEXTURE_SIZE / 2.0) * blocksPerPixel);
                int blockZ = (int) Math.floor(viewCenterZ + (py - TEXTURE_SIZE / 2.0) * blocksPerPixel);
                int c = data.getColorAt(minecraft, blockX, blockZ);
                int color = (c == 0) ? NOT_SCANNED : c;

                // NativeImage uses ABGR format
                img.setPixelABGR(px, py, argbToAbgr(color));
            }
        }
        mapTexture.upload();
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void drawPlayer(GuiGraphics g, int mapLeft, int mapTop, int mapSize) {
        if (minecraft.player == null) return;

        double px = minecraft.player.getX();
        double pz = minecraft.player.getZ();
        float yaw = minecraft.player.getYRot();

        double cos = Math.cos(viewYaw);
        double sin = Math.sin(viewYaw);
        double relX = px - viewCenterX;
        double relZ = pz - viewCenterZ;
        double rotatedX = relX * cos - relZ * sin;
        double rotatedZ = relX * sin + relZ * cos;
        int screenX = (int)(mapLeft + mapSize / 2.0 + rotatedX / blocksPerPixel);
        int screenZ = (int)(mapTop  + mapSize * 0.58 + rotatedZ * viewPitch / blocksPerPixel);

        if (screenX < mapLeft || screenX > mapLeft + mapSize ||
            screenZ < mapTop  || screenZ > mapTop  + mapSize) return;

        // Red dot with black border
        g.fill(screenX - 3, screenZ - 3, screenX + 3, screenZ + 3, 0xFF000000);
        g.fill(screenX - 2, screenZ - 2, screenX + 2, screenZ + 2, 0xFFFF2222);

        // White direction indicator
        double rad = Math.toRadians(yaw);
        int tipX = (int)(screenX + Math.sin(rad) * 6);
        int tipZ = (int)(screenZ - Math.cos(rad) * 6);
        g.fill(tipX - 1, tipZ - 1, tipX + 1, tipZ + 1, 0xFFFFFFFF);
    }

    private void drawTopBar(GuiGraphics g, int mapLeft, int mapTop, int mapSize) {
        g.fill(mapLeft, mapTop - 18, mapLeft + mapSize, mapTop - 2, PANEL_BG);
        g.drawCenteredString(minecraft.font,
                "§b§lVoxyMap §8│ §7Mapa świata Voxy LOD",
                width / 2, mapTop - 14, 0xFFFFFF);
    }

    private void drawWorldViewerOverlay(GuiGraphics g) {
        int slide = (int) ((1.0 - openingProgress()) * 28.0);
        int topX = 12;
        int topY = 12 - slide;
        int topW = Math.min(width - 24, 300);
        g.fill(topX, topY, topX + topW, topY + 42, 0x99030914);
        g.fill(topX, topY, topX + 2, topY + 42, 0xFF3BA4FF);
        if (minecraft.player != null) {
            int px = (int) minecraft.player.getX();
            int py = (int) minecraft.player.getY();
            int pz = (int) minecraft.player.getZ();
            g.drawString(minecraft.font, Component.translatable("overlay.voxymap.player").getString() + "  " + px + " / " + py + " / " + pz, topX + 10, topY + 7, 0xFFFFFFFF);
        }
        g.drawString(minecraft.font, Component.translatable("overlay.voxymap.camera").getString() + "  " + (int) viewCenterX + " / " + (int) viewCenterZ
                        + "   " + Component.translatable("overlay.voxymap.zoom").getString() + " " + String.format("%.2f", blocksPerPixel),
                topX + 10, topY + 23, 0xFFBFD8FF);

        String keys = Component.translatable("overlay.voxymap.keys").getString();
        int keyW = Math.min(width - 24, minecraft.font.width(keys) + 20);
        int keyX = 12;
        int keyY = height - 32 + slide;
        g.fill(keyX, keyY, keyX + keyW, keyY + 20, 0x88030914);
        g.fill(keyX, keyY, keyX + 2, keyY + 20, 0xFF3BA4FF);
        g.drawString(minecraft.font, keys, keyX + 10, keyY + 6, 0xFFE9F2FF);

        int cx = width / 2;
        int cy = height / 2;
        g.fill(cx - 5, cy, cx - 2, cy + 1, 0xAAFFFFFF);
        g.fill(cx + 3, cy, cx + 6, cy + 1, 0xAAFFFFFF);
        g.fill(cx, cy - 5, cx + 1, cy - 2, 0xAAFFFFFF);
        g.fill(cx, cy + 3, cx + 1, cy + 6, 0xAAFFFFFF);
    }

    private void drawBottomBar(GuiGraphics g, int mapLeft, int mapTop, int mapSize, int mx, int my) {
        int barY = mapTop + mapSize + 4;
        g.fill(mapLeft, barY, mapLeft + mapSize, barY + BOTTOM_BAR_HEIGHT, PANEL_BG);

        if (minecraft.player != null) {
            int px = (int)minecraft.player.getX(), py = (int)minecraft.player.getY(), pz = (int)minecraft.player.getZ();
            g.drawString(minecraft.font, "§7Gracz: §a" + px + " §8/ §e" + py + " §8/ §a" + pz,
                    mapLeft + 5, barY + 3, 0xFFFFFF);
        }

        if (mx >= mapLeft && mx <= mapLeft + mapSize && my >= mapTop && my <= mapTop + mapSize) {
            int cx = (int) Math.floor(viewCenterX + (mx - (mapLeft + mapSize / 2.0)) * blocksPerPixel);
            int cz = (int) Math.floor(viewCenterZ + (my - (mapTop  + mapSize / 2.0)) * blocksPerPixel);
            g.drawString(minecraft.font, "§7Kursor: §f" + cx + "§8, §f" + cz,
                    mapLeft + 5, barY + 14, 0xFFFFFF);
        }

        String vs = VoxyBridge.isVoxyPresent() ? "§aVoxy §2✓" : "§cBrak Voxy";
        g.drawString(minecraft.font, vs, mapLeft + mapSize - 90, barY + 3, 0xFFFFFF);
        g.drawString(minecraft.font, String.format("§7%.1f blk/px", blocksPerPixel),
                mapLeft + mapSize - 90, barY + 14, 0xFFFFFF);
        g.drawString(minecraft.font, "§8[ESC/M] Zamknij  [LPM] Przesuń  [Scroll] Zoom  [C] Centrum  [WASD] Przesuń",
                mapLeft + 5, barY + 26, 0x888888);
        if (data != null) {
            g.drawString(minecraft.font, "\u00A78" + data.getDebugStatus(), mapLeft + 5, barY + 38, 0x888888);
        }
    }

    private void drawCompass(GuiGraphics g, int cx, int cy) {
        int r = 20;
        g.fill(cx - r, cy - r, cx + r, cy + r, 0xAA000011);
        g.fill(cx - r, cy - r, cx + r, cy - r + 1, BORDER);
        g.fill(cx - r, cy + r - 1, cx + r, cy + r, BORDER);
        g.drawCenteredString(minecraft.font, "§cN", cx,       cy - r + 2,  0xFF0000);
        g.drawCenteredString(minecraft.font, "§7S", cx,       cy + r - 10, 0xAAAAAA);
        g.drawString(minecraft.font,         "§7E", cx + r - 7, cy - 4,    0xAAAAAA);
        g.drawString(minecraft.font,         "§7W", cx - r + 1, cy - 4,    0xAAAAAA);
    }

    private static final class TerrainTile {
        private final double x;
        private final double y;
        private final double depth;
        private final int size;
        private final double heightPixels;
        private final int color;

        private TerrainTile(double x, double y, double depth, int size, double heightPixels, int color) {
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.size = size;
            this.heightPixels = heightPixels;
            this.color = color;
        }
    }

    // ======================= Input (MC 1.21.11 API) =======================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double factor = scrollY > 0 ? 0.8 : 1.25;
        blocksPerPixel = Mth.clamp(blocksPerPixel * factor, 0.125, 256.0);
        syncWorldCamera();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isInside) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
            dragging = true;
            dragStartMouseX = event.x();
            dragStartMouseY = event.y();
            dragStartViewX  = viewCenterX;
            dragStartViewZ  = viewCenterZ;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_2) {
            rotating = true;
            dragStartMouseX = event.x();
            dragStartMouseY = event.y();
            dragStartYaw = viewYaw;
            dragStartPitch = viewPitch;
            return true;
        }
        return super.mouseClicked(event, isInside);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
            dragging = false;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_2) {
            rotating = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            double dragRight = -(event.x() - dragStartMouseX) * blocksPerPixel;
            double dragForward = -(event.y() - dragStartMouseY) * blocksPerPixel;
            double forwardX = -Math.sin(viewYaw);
            double forwardZ = Math.cos(viewYaw);
            double rightX = Math.cos(viewYaw);
            double rightZ = Math.sin(viewYaw);
            viewCenterX = dragStartViewX + rightX * dragRight + forwardX * dragForward;
            viewCenterZ = dragStartViewZ + rightZ * dragRight + forwardZ * dragForward;
            syncWorldCamera();
            return true;
        }
        if (rotating) {
            viewYaw = dragStartYaw + (event.x() - dragStartMouseX) * 0.01;
            viewPitch = Mth.clamp(dragStartPitch + (event.y() - dragStartMouseY) * 0.003, 0.2, 1.1);
            syncWorldCamera();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || VoxyMapClient.openMapKey.matches(event)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_W || keyCode == GLFW.GLFW_KEY_S ||
                keyCode == GLFW.GLFW_KEY_A || keyCode == GLFW.GLFW_KEY_D ||
                keyCode == GLFW.GLFW_KEY_Q || keyCode == GLFW.GLFW_KEY_E ||
                keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Z) { heightScale = Mth.clamp(heightScale * 0.85, 0.15, 2.5); syncWorldCamera(); return true; }
        if (keyCode == GLFW.GLFW_KEY_X) { heightScale = Mth.clamp(heightScale * 1.18, 0.15, 2.5); syncWorldCamera(); return true; }
        if (keyCode == GLFW.GLFW_KEY_C && minecraft.player != null) {
            viewCenterX = minecraft.player.getX();
            viewCenterZ = minecraft.player.getZ();
            syncWorldCamera();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R && data != null) {
            data.resetScan();
            nativeRendererFailed = true;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_T) {
            useNativeVoxyRenderer = false;
            nativeRendererFailed = true;
            VoxyMapClient.LOGGER.warn("[VoxyMap] Native Voxy renderer preview is disabled because direct Voxy rendering inside Screen.render is experimental. VoxyMap data rendering is tested with Voxy {}. Your Voxy version: {}.",
                    VoxyBridge.testedVoxyVersionsText(), VoxyBridge.getVoxyVersion());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            blocksPerPixel = Mth.clamp(blocksPerPixel * 0.8, 0.125, 256.0);
            syncWorldCamera();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            blocksPerPixel = Mth.clamp(blocksPerPixel * 1.25, 0.125, 256.0);
            syncWorldCamera();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        VoxyMapCameraController.deactivate();
        VoxyBridge.restoreEnvironmentalFogAfterMap();
        releaseTexture();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
