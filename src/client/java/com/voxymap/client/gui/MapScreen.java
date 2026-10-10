package com.voxymap.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxymap.client.VoxyMapClient;
import com.voxymap.client.integration.XaeroWorldMapBridge;
import com.voxymap.client.map.MapRenderSettingsGuard;
import com.voxymap.client.map.MapView;
import com.voxymap.client.map.VoxyBridge;
import com.voxymap.client.map.VoxyMapCameraController;
import com.voxymap.client.map.VoxyMapGuiRenderer;
import com.voxymap.client.map.VoxyMapSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLScancode;

public class MapScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.voxymap.title");

    private static final long OPEN_ANIMATION_NANOS = 850_000_000L;
    private static final long XAERO_TRANSITION_NANOS = 380_000_000L;
    /** Focus height when there is no loaded ground to aim at. */
    private static final double FALLBACK_FOCUS_LEVEL = 64.0;
    private static final double MAX_KEYBOARD_CAMERA_SPEED = 640.0;
    private static final double MAX_DRAG_STEP_BLOCKS = 1536.0;
    private double viewCenterX;
    private double viewCenterY;
    private double viewCenterZ;
    private double blocksPerPixel = 4.0;
    private double viewYaw = Math.toRadians(45.0);
    private double viewPitch = 0.68;

    private boolean dragging = false;
    private boolean middleDragging = false;
    private double dragStartMouseX;
    private double dragStartYaw;
    private double dragLastMouseX;
    private double dragLastMouseY;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private double moveVelocityX = 0.0;
    private double moveVelocityZ = 0.0;
    private long lastFrameNanos = 0L;
    private long openedAtNanos = 0L;
    private boolean openLogged = false;
    private boolean xaeroTransitionActive = false;
    private long xaeroTransitionStartedAt = 0L;
    private int xaeroButtonX = -1;
    private int xaeroButtonY = -1;
    private int xaeroButtonW = 0;
    private int xaeroButtonH = 0;
    private boolean settingsOpen = false;
    private int settingsButtonX = -1;
    private int settingsButtonY = -1;
    private int settingsButtonW = 0;
    private int settingsButtonH = 0;
    private int settingsPanelX = -1;
    private int settingsPanelY = -1;
    private int settingsPanelW = 0;
    private int settingsPanelH = 0;
    private int speedMinusX = -1;
    private int speedMinusY = -1;
    private int speedPlusX = -1;
    private int speedPlusY = -1;
    private int pauseRowY = -1;
    private int speedRowY = -1;
    private final PlayerMarker playerMarker = new PlayerMarker();
    private static final int SETTINGS_ROW_HEIGHT = 24;
    private static final int SPEED_BUTTON_SIZE = 18;
    private static final int HUD_MARGIN = 12;
    private static final int HUD_GAP = 6;
    private static final int HUD_BUTTON_HEIGHT = 24;
    private static final int KEY_HINT_HEIGHT = 22;

    private static final int BG = 0xFF080810;

    public MapScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        super.init();
        openedAtNanos = System.nanoTime();
        lastFrameNanos = 0L;
        viewCenterY = FALLBACK_FOCUS_LEVEL;
        moveFocusToPlayer();
        if (!isUnsupportedDimension()) {
            VoxyBridge.suppressEnvironmentalFogForMap();
            MapRenderSettingsGuard.applyForMap(minecraft);
            syncWorldCamera();
            VoxyMapGuiRenderer.open(minecraft);
        } else {
            VoxyMapCameraController.deactivate();
        }
        if (!openLogged) {
            VoxyMapClient.LOGGER.info("[VoxyMap] Map opened.");
            openLogged = true;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (isUnsupportedDimension()) {
            VoxyMapGuiRenderer.close(minecraft);
            VoxyMapCameraController.deactivate();
            drawUnsupportedDimension(g);
            drawOpeningAnimation(g);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }

        // The world on screen was rendered with the camera as it was before this frame's input.
        MapView renderedView = captureView();
        updateSmoothControls();
        VoxyBridge.suppressEnvironmentalFogForMap();
        MapRenderSettingsGuard.applyForMap(minecraft);
        syncWorldCamera();
        drawPlayerMarker(g, renderedView, delta);
        drawWorldViewerOverlay(g, mouseX, mouseY);
        drawOpeningAnimation(g);
        if (xaeroTransitionActive) {
            drawXaeroTransition(g);
            if (System.nanoTime() - xaeroTransitionStartedAt >= XAERO_TRANSITION_NANOS) {
                openXaeroWorldMap();
                return;
            }
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
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

        double forwardInput = keyDown(InputConstants.KEY_W) || keyDown(InputConstants.KEY_UP) ? 1.0 : 0.0;
        forwardInput -= keyDown(InputConstants.KEY_S) || keyDown(InputConstants.KEY_DOWN) ? 1.0 : 0.0;
        double strafeInput = keyDown(InputConstants.KEY_A) || keyDown(InputConstants.KEY_LEFT) ? 1.0 : 0.0;
        strafeInput -= keyDown(InputConstants.KEY_D) || keyDown(InputConstants.KEY_RIGHT) ? 1.0 : 0.0;

        double horizontalInput = Math.hypot(forwardInput, strafeInput);
        if (horizontalInput > 1.0) {
            forwardInput /= horizontalInput;
            strafeInput /= horizontalInput;
        }

        double speedBoost = keyDown(InputConstants.KEY_LSHIFT) || keyDown(InputConstants.KEY_RSHIFT) ? 2.5 : 1.0;
        double moveSpeed = Math.max(12.0, blocksPerPixel * 76.0) * speedBoost * VoxyMapSettings.cameraSpeedMultiplier();
        moveSpeed = Math.min(moveSpeed, MAX_KEYBOARD_CAMERA_SPEED * speedBoost);
        double forwardX = -Math.sin(viewYaw);
        double forwardZ = Math.cos(viewYaw);
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double targetVX = dragging ? 0.0 : (forwardX * forwardInput + rightX * strafeInput) * moveSpeed;
        double targetVZ = dragging ? 0.0 : (forwardZ * forwardInput + rightZ * strafeInput) * moveSpeed;

        double response = 1.0 - Math.exp(-dt * 10.0);
        moveVelocityX = Mth.lerp(response, moveVelocityX, targetVX);
        moveVelocityZ = Mth.lerp(response, moveVelocityZ, targetVZ);

        if (!dragging) {
            viewCenterX += moveVelocityX * dt;
            viewCenterZ += moveVelocityZ * dt;
        }
    }

    private static boolean keyDown(int scancode) {
        return InputConstants.isKeyDown(scancode);
    }

    private MapView.GroundPoint mapPointAt(double mouseX, double mouseY) {
        MapView view = captureView();
        return view == null ? null : view.groundPointAt(mouseX, mouseY, viewCenterY);
    }

    private MapView captureView() {
        var window = minecraft.getWindow();
        double aspectRatio = window.getHeight() > 0 ? window.getWidth() / (double) window.getHeight() : 1.0;
        return MapView.capture(width, height, aspectRatio);
    }

    private void keepMapPointUnderMouse(MapView.GroundPoint anchor, double mouseX, double mouseY) {
        MapView.GroundPoint current = mapPointAt(mouseX, mouseY);
        if (anchor == null || current == null) {
            return;
        }
        viewCenterX += anchor.x() - current.x();
        viewCenterZ += anchor.z() - current.z();
    }

    /**
     * Puts the map focus on the ground under the player. The camera aims at a point on the
     * ground; aiming at a fixed height far above the terrain pushed what is on screen away
     * from the player and left the top of a zoomed-in map empty.
     */
    private void moveFocusToPlayer() {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        viewCenterX = player.getX();
        viewCenterY = groundHeightAt(player.getX(), player.getZ(), player.getY());
        viewCenterZ = player.getZ();
    }

    private void centerOnPlayer() {
        moveFocusToPlayer();
        moveVelocityX = 0.0;
        moveVelocityZ = 0.0;
        syncWorldCamera();
    }

    /** The top of the terrain at a column, or {@code fallback} when that column is not loaded. */
    private double groundHeightAt(double x, double z, double fallback) {
        ClientLevel level = minecraft.level;
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        if (level == null || !level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
            return fallback;
        }
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        return height > level.getMinY() ? height : fallback;
    }

    private void drawPlayerMarker(GuiGraphicsExtractor g, MapView view, float partialTick) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        Vec3 position = player.getPosition(partialTick);
        double groundY = groundHeightAt(position.x, position.z, position.y);
        // Keep the off-screen marker clear of the info panel (42 tall with a player) and the key hints.
        int safeTop = HUD_MARGIN + 42 + HUD_GAP + 14;
        int safeBottom = height - HUD_MARGIN - KEY_HINT_HEIGHT - HUD_GAP - 14;
        playerMarker.draw(g, minecraft, view, position.x, groundY, position.z, player.getViewYRot(partialTick),
                viewCenterX, viewCenterY, viewCenterZ, Math.max(1.0, blocksPerPixel * 16.0),
                HUD_MARGIN + 14, safeTop, width - HUD_MARGIN - 14, Math.max(safeTop, safeBottom));
    }

    private void panByPixels(double deltaX, double deltaY) {
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double forwardX = -Math.sin(viewYaw);
        double forwardZ = Math.cos(viewYaw);
        double pitch = Math.toRadians(VoxyMapCameraController.cameraPitch());
        double forwardScale = 1.0 / Math.max(0.35, Math.sin(pitch));
        double sideDistance = deltaX * blocksPerPixel;
        double forwardDistance = deltaY * blocksPerPixel * forwardScale;
        double distance = Math.hypot(sideDistance, forwardDistance);
        if (distance > MAX_DRAG_STEP_BLOCKS) {
            double scale = MAX_DRAG_STEP_BLOCKS / distance;
            sideDistance *= scale;
            forwardDistance *= scale;
        }

        viewCenterX += rightX * sideDistance + forwardX * forwardDistance;
        viewCenterZ += rightZ * sideDistance + forwardZ * forwardDistance;
    }

    @Override
    public void tick() {
        if (!isUnsupportedDimension()) {
            syncWorldCamera();
        }
    }

    private void syncWorldCamera() {
        if (!isUnsupportedDimension()) {
            VoxyMapCameraController.update(minecraft, viewCenterX, viewCenterY, viewCenterZ, viewYaw, viewPitch, blocksPerPixel);
            MapView view = captureView();
            if (view != null) {
                VoxyBridge.coverRenderDistanceForMap(view.groundReach(viewCenterY));
            }
        } else {
            VoxyMapCameraController.deactivate();
        }
    }

    private boolean isUnsupportedDimension() {
        if (minecraft == null || minecraft.level == null) return false;
        String dimension = minecraft.level.dimension().identifier().toString();
        return "minecraft:the_nether".equals(dimension);
    }

    private String unsupportedDimensionMessageKey() {
        if (minecraft != null && minecraft.level != null
                && "minecraft:the_end".equals(minecraft.level.dimension().identifier().toString())) {
            return "screen.voxymap.unsupported.end";
        }
        return "screen.voxymap.unsupported.nether";
    }

    private void drawUnsupportedDimension(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, BG);
        int boxW = Math.min(width - 40, 420);
        int boxH = 82;
        int x = (width - boxW) / 2;
        int y = (height - boxH) / 2;
        g.fill(x - 2, y - 2, x + boxW + 2, y + boxH + 2, 0x553BA4FF);
        g.fill(x, y, x + boxW, y + boxH, 0xEE030914);
        g.fill(x, y, x + 4, y + boxH, 0xFF3BA4FF);
        g.centeredText(minecraft.font, Component.translatable("screen.voxymap.unsupported.title"), width / 2, y + 22, 0xFFFFFFFF);
        g.centeredText(minecraft.font, Component.translatable(unsupportedDimensionMessageKey()), width / 2, y + 46, 0xFFBFD8FF);
    }

    private void drawOpeningAnimation(GuiGraphicsExtractor g) {
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
            g.centeredText(minecraft.font, title, width / 2, height / 2 - 5, color);
        }
    }

    private void drawWorldViewerOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int slide = (int) ((1.0 - openingProgress()) * 28.0);
        int topX = HUD_MARGIN;
        int topY = HUD_MARGIN - slide;
        String title = "VoxyMap 3D";
        String playerText = "";
        if (minecraft.player != null) {
            int px = (int) minecraft.player.getX();
            int py = (int) minecraft.player.getY();
            int pz = (int) minecraft.player.getZ();
            playerText = Component.translatable("overlay.voxymap.player").getString() + "  " + px + " / " + py + " / " + pz;
        }
        String cameraText = Component.translatable("overlay.voxymap.camera").getString() + "  " + (int) viewCenterX + " / " + (int) viewCenterY + " / " + (int) viewCenterZ
                + "   " + Component.translatable("overlay.voxymap.zoom").getString() + " " + String.format("%.2f", blocksPerPixel);
        int topW = Math.max(1, Math.min(width - HUD_MARGIN * 2, Math.max(minecraft.font.width(title), Math.max(minecraft.font.width(playerText), minecraft.font.width(cameraText))) + 20));
        int topH = minecraft.player != null ? 42 : 30;
        drawGlassPanel(g, topX, topY, topW, topH, 0xAA030914, 0xFF3BA4FF);
        g.text(minecraft.font, title, topX + 10, topY + 5, 0xFFFFFFFF);
        if (minecraft.player != null) {
            g.text(minecraft.font, playerText, topX + 10, topY + 18, 0xFFDFF8FF);
        }
        g.text(minecraft.font, cameraText, topX + 10, topY + (minecraft.player != null ? 31 : 18), 0xFF9FD8FF);

        int footerTop = drawFooter(g, slide);
        drawXaeroButton(g, mouseX, mouseY, slide, topX, topY, topW, topH);
        drawSettingsButton(g, mouseX, mouseY, slide);
        if (settingsOpen) {
            drawSettingsPanel(g, footerTop);
        }
    }

    private void drawGlassPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int accent) {
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + 2, y + h, accent);
        g.fill(x, y, x + w, y + 1, 0x33FFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0x55000000);
    }

    private void drawSettingsButton(GuiGraphicsExtractor g, int mouseX, int mouseY, int slide) {
        String label = Component.translatable("overlay.voxymap.settings").getString();
        settingsButtonW = Math.min(width - HUD_MARGIN * 2, Math.min(156, Math.max(104, minecraft.font.width(label) + 28)));
        settingsButtonH = HUD_BUTTON_HEIGHT;
        settingsButtonX = width - settingsButtonW - HUD_MARGIN;
        settingsButtonY = xaeroButtonW > 0 ? xaeroButtonY + xaeroButtonH + HUD_GAP : HUD_MARGIN - slide;
        boolean hovered = isInsideSettingsButton(mouseX, mouseY);
        int bg = settingsOpen ? 0xD014283D : (hovered ? 0xCC112338 : 0x99030914);
        int accent = settingsOpen || hovered ? 0xFF7DEBFF : 0xFF3BA4FF;

        drawGlassPanel(g, settingsButtonX, settingsButtonY, settingsButtonW, settingsButtonH, bg, accent);
        int gearX = settingsButtonX + 13;
        int gearY = settingsButtonY + 12;
        g.fill(gearX - 4, gearY - 1, gearX + 4, gearY + 1, accent);
        g.fill(gearX - 1, gearY - 4, gearX + 1, gearY + 4, accent);
        g.text(minecraft.font, label, settingsButtonX + 26, settingsButtonY + 8, 0xFFE9F2FF);
    }

    private void drawSettingsPanel(GuiGraphicsExtractor g, int footerTop) {
        settingsPanelW = Math.min(360, width - HUD_MARGIN * 2);
        settingsPanelH = 94;
        settingsPanelX = width - settingsPanelW - HUD_MARGIN;
        settingsPanelY = Math.max(HUD_MARGIN, footerTop - settingsPanelH - HUD_GAP);
        drawGlassPanel(g, settingsPanelX, settingsPanelY, settingsPanelW, settingsPanelH, 0xDD030914, 0xFF3BA4FF);

        int x = settingsPanelX + 12;
        int y = settingsPanelY + 10;
        pauseRowY = y + 20;
        speedRowY = y + 44;
        g.text(minecraft.font, Component.translatable("screen.voxymap.settings.title"), x, y, 0xFFFFFFFF);
        drawSettingValue(g, x, pauseRowY, "screen.voxymap.settings.pause", onOff(VoxyMapSettings.pauseSingleplayer()), 0);
        drawSettingValue(g, x, speedRowY, "screen.voxymap.settings.speed", String.format("%.2fx", VoxyMapSettings.cameraSpeedMultiplier()), 1);
    }

    private void drawSettingValue(GuiGraphicsExtractor g, int x, int y, String labelKey, String value, int row) {
        int rowX = settingsPanelX + 8;
        int rowW = settingsPanelW - 16;
        g.fill(rowX, y, rowX + rowW, y + SETTINGS_ROW_HEIGHT - 2, 0x66102034);
        g.fill(rowX, y, rowX + 2, y + SETTINGS_ROW_HEIGHT - 2, 0xFF3BA4FF);
        g.text(minecraft.font, Component.translatable(labelKey), x, y + 7, 0xFFDFF8FF);
        int valueW = Math.max(62, minecraft.font.width(value) + 14);
        int valueX = settingsPanelX + settingsPanelW - valueW - 14;
        if (row == 1) {
            valueX -= (SPEED_BUTTON_SIZE + 6);
        }
        drawValueBox(g, valueX, y + 3, valueW, 16, value);
        if (row == 1) {
            speedMinusX = valueX - SPEED_BUTTON_SIZE - 6;
            speedMinusY = y + 2;
            speedPlusX = valueX + valueW + 6;
            speedPlusY = y + 2;
            drawSmallButton(g, speedMinusX, speedMinusY, "-", 0xCC102034);
            drawSmallButton(g, speedPlusX, speedPlusY, "+", 0xCC102034);
        }
    }

    private void drawValueBox(GuiGraphicsExtractor g, int x, int y, int w, int h, String value) {
        g.fill(x, y, x + w, y + h, 0xAA102034);
        g.fill(x, y, x + 2, y + h, 0xFF3BA4FF);
        g.text(minecraft.font, value, x + 7, y + 4, 0xFFFFFFFF);
    }

    private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, String label, int bg) {
        g.fill(x, y, x + SPEED_BUTTON_SIZE, y + SPEED_BUTTON_SIZE, bg);
        g.fill(x, y, x + 2, y + SPEED_BUTTON_SIZE, 0xFF7DEBFF);
        g.centeredText(minecraft.font, label, x + SPEED_BUTTON_SIZE / 2, y + 5, 0xFFFFFFFF);
    }

    private int drawFooter(GuiGraphicsExtractor g, int slide) {
        String[][] hints = keyHints();
        int contentWidth = keyHintsWidth(hints);
        float availableWidth = Math.max(1, width - HUD_MARGIN * 2);
        float scale = Math.min(1.0f, availableWidth / contentWidth);
        int visualHeight = Math.max(1, Math.round(KEY_HINT_HEIGHT * scale));
        int footerX = HUD_MARGIN;
        int footerY = height - HUD_MARGIN - visualHeight + slide;

        g.pose().pushMatrix();
        g.pose().translate(footerX, footerY);
        g.pose().scale(scale, scale);
        drawKeyHints(g, hints, 0, 0);
        g.pose().popMatrix();
        return footerY;
    }

    private String[][] keyHints() {
        return new String[][] {
                {Component.translatable("overlay.voxymap.key.drag.button").getString(), Component.translatable("overlay.voxymap.key.drag").getString()},
                {Component.translatable("overlay.voxymap.key.rotate.button").getString(), Component.translatable("overlay.voxymap.key.rotate").getString()},
                {Component.translatable("overlay.voxymap.key.move.button").getString(), Component.translatable("overlay.voxymap.key.move").getString()},
                {Component.translatable("overlay.voxymap.key.fast.button").getString(), Component.translatable("overlay.voxymap.key.fast").getString()},
                {Component.translatable("overlay.voxymap.key.zoom.button").getString(), Component.translatable("overlay.voxymap.key.zoom").getString()},
                {Component.translatable("overlay.voxymap.key.center.button").getString(), Component.translatable("overlay.voxymap.key.center").getString()},
                {Component.translatable("overlay.voxymap.key.close.button").getString(), Component.translatable("overlay.voxymap.key.close").getString()}
        };
    }

    private int keyHintsWidth(String[][] hints) {
        int width = 0;
        for (String[] hint : hints) {
            width += hintWidth(hint) + HUD_GAP;
        }
        return width - HUD_GAP;
    }

    private void drawKeyHints(GuiGraphicsExtractor g, String[][] hints, int x, int y) {
        int currentX = x;
        for (String[] hint : hints) {
            int w = hintWidth(hint);
            drawGlassPanel(g, currentX, y, w, KEY_HINT_HEIGHT, 0x99030914, 0xFF3BA4FF);
            g.text(minecraft.font, hint[0], currentX + 9, y + 7, 0xFFFFFFFF);
            g.text(minecraft.font, hint[1], currentX + 14 + minecraft.font.width(hint[0]), y + 7, 0xFFBFD8FF);
            currentX += w + HUD_GAP;
        }
    }

    private int hintWidth(String[] hint) {
        return minecraft.font.width(hint[0]) + minecraft.font.width(hint[1]) + 22;
    }

    private String onOff(boolean enabled) {
        return Component.translatable(enabled ? "screen.voxymap.settings.on" : "screen.voxymap.settings.off").getString();
    }

    private void drawXaeroButton(GuiGraphicsExtractor g, int mouseX, int mouseY, int slide, int topX, int topY, int topW, int topH) {
        if (!XaeroWorldMapBridge.isAvailable()) {
            xaeroButtonX = -1;
            xaeroButtonY = -1;
            xaeroButtonW = 0;
            xaeroButtonH = 0;
            return;
        }

        String label = Component.translatable("overlay.voxymap.xaero").getString();
        xaeroButtonW = Math.min(width - HUD_MARGIN * 2, Math.min(180, Math.max(118, minecraft.font.width(label) + 36)));
        xaeroButtonH = HUD_BUTTON_HEIGHT;
        xaeroButtonX = width - xaeroButtonW - HUD_MARGIN;
        xaeroButtonY = HUD_MARGIN - slide;
        if (xaeroButtonX < topX + topW + HUD_GAP) {
            xaeroButtonY = topY + topH + HUD_GAP;
        }
        boolean hovered = isInsideXaeroButton(mouseX, mouseY);
        int bg = hovered ? 0xCC112338 : 0x99030914;
        int accent = hovered ? 0xFF7DEBFF : 0xFF3BA4FF;

        g.fill(xaeroButtonX, xaeroButtonY, xaeroButtonX + xaeroButtonW, xaeroButtonY + xaeroButtonH, bg);
        g.fill(xaeroButtonX, xaeroButtonY, xaeroButtonX + 2, xaeroButtonY + xaeroButtonH, accent);
        int iconX = xaeroButtonX + 12;
        int iconY = xaeroButtonY + 12;
        g.fill(iconX - 5, iconY - 5, iconX + 5, iconY + 5, 0xFF102A38);
        g.fill(iconX - 4, iconY - 1, iconX + 4, iconY + 1, accent);
        g.fill(iconX - 1, iconY - 4, iconX + 1, iconY + 4, accent);
        g.text(minecraft.font, label, xaeroButtonX + 28, xaeroButtonY + 8, 0xFFE9F2FF);
    }

    private boolean isInsideXaeroButton(double mouseX, double mouseY) {
        return xaeroButtonW > 0
                && mouseX >= xaeroButtonX && mouseX <= xaeroButtonX + xaeroButtonW
                && mouseY >= xaeroButtonY && mouseY <= xaeroButtonY + xaeroButtonH;
    }

    private boolean isInsideSettingsButton(double mouseX, double mouseY) {
        return settingsButtonW > 0
                && mouseX >= settingsButtonX && mouseX <= settingsButtonX + settingsButtonW
                && mouseY >= settingsButtonY && mouseY <= settingsButtonY + settingsButtonH;
    }

    private boolean isInsideSettingsPanel(double mouseX, double mouseY) {
        return settingsOpen && settingsPanelW > 0
                && mouseX >= settingsPanelX && mouseX <= settingsPanelX + settingsPanelW
                && mouseY >= settingsPanelY && mouseY <= settingsPanelY + settingsPanelH;
    }

    private boolean handleSettingsClick(double mouseX, double mouseY) {
        if (isInsideSettingsButton(mouseX, mouseY)) {
            settingsOpen = !settingsOpen;
            return true;
        }
        if (!isInsideSettingsPanel(mouseX, mouseY)) {
            if (settingsOpen) {
                settingsOpen = false;
                return true;
            }
            return false;
        }

        if (mouseX >= speedMinusX && mouseX <= speedMinusX + SPEED_BUTTON_SIZE
                && mouseY >= speedMinusY && mouseY <= speedMinusY + SPEED_BUTTON_SIZE) {
            VoxyMapSettings.changeCameraSpeed(-0.25);
            return true;
        }
        if (mouseX >= speedPlusX && mouseX <= speedPlusX + SPEED_BUTTON_SIZE
                && mouseY >= speedPlusY && mouseY <= speedPlusY + SPEED_BUTTON_SIZE) {
            VoxyMapSettings.changeCameraSpeed(0.25);
            return true;
        }

        if (isInsideSettingsRow(mouseX, mouseY, pauseRowY)) {
            VoxyMapSettings.togglePauseSingleplayer();
        } else if (isInsideSettingsRow(mouseX, mouseY, speedRowY)) {
            double rowMid = settingsPanelX + settingsPanelW / 2.0;
            VoxyMapSettings.changeCameraSpeed(mouseX < rowMid ? -0.25 : 0.25);
        }
        return true;
    }

    private boolean isInsideSettingsRow(double mouseX, double mouseY, int rowY) {
        return rowY >= 0
                && mouseX >= settingsPanelX + 8 && mouseX <= settingsPanelX + settingsPanelW - 8
                && mouseY >= rowY && mouseY <= rowY + SETTINGS_ROW_HEIGHT - 2;
    }

    private void startXaeroTransition() {
        if (xaeroTransitionActive) return;
        dragging = false;
        middleDragging = false;
        xaeroTransitionActive = true;
        xaeroTransitionStartedAt = System.nanoTime();
    }

    private void drawXaeroTransition(GuiGraphicsExtractor g) {
        double raw = (System.nanoTime() - xaeroTransitionStartedAt) / (double) XAERO_TRANSITION_NANOS;
        raw = Mth.clamp(raw, 0.0, 1.0);
        double eased = 1.0 - Math.pow(1.0 - raw, 3.0);
        int maxRadius = Math.max(width, height) + 120;
        int radius = (int) (maxRadius * eased);
        int originX = xaeroButtonX > 0 ? xaeroButtonX + xaeroButtonW / 2 : width - 80;
        int originY = xaeroButtonY > 0 ? xaeroButtonY + xaeroButtonH / 2 : 24;

        g.fill(0, 0, width, height, (int) (0x55000000 | ((long) (80 * eased) << 24)));
        g.fill(originX - radius, originY - radius / 3, originX + radius, originY + radius / 3, 0xDD07111F);
        g.fill(originX - radius / 3, originY - radius, originX + radius / 3, originY + radius, 0xAA0B3148);
        g.centeredText(minecraft.font, Component.translatable("overlay.voxymap.xaero.transition"),
                width / 2, height / 2 + 22, 0xFFE9F2FF);
    }

    private void openXaeroWorldMap() {
        restoreVoxySettings();
        VoxyMapGuiRenderer.close(minecraft);
        VoxyMapCameraController.deactivate();
        MapRenderSettingsGuard.restoreAfterMap(minecraft);

        if (XaeroWorldMapBridge.openWorldMap(minecraft)) {
            return;
        }

        VoxyBridge.suppressEnvironmentalFogForMap();
        MapRenderSettingsGuard.applyForMap(minecraft);
        syncWorldCamera();
        VoxyMapGuiRenderer.open(minecraft);
        xaeroTransitionActive = false;
        xaeroTransitionStartedAt = 0L;
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable("message.voxymap.xaero_open_failed"));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return true;
        }
        double factor = Math.pow(0.82, scrollY);
        zoomAt(mouseX, mouseY, factor);
        return true;
    }

    private void zoomAt(double mouseX, double mouseY, double factor) {
        double oldBlocksPerPixel = blocksPerPixel;
        double newBlocksPerPixel = Mth.clamp(oldBlocksPerPixel * factor, 0.125, VoxyMapCameraController.MAX_BLOCKS_PER_PIXEL);
        if (newBlocksPerPixel == oldBlocksPerPixel) {
            return;
        }

        syncWorldCamera();
        MapView.GroundPoint anchor = mapPointAt(mouseX, mouseY);
        blocksPerPixel = newBlocksPerPixel;
        syncWorldCamera();
        keepMapPointUnderMouse(anchor, mouseX, mouseY);
        syncWorldCamera();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isInside) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && handleSettingsClick(event.x(), event.y())) {
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && isInsideXaeroButton(event.x(), event.y())) {
            startXaeroTransition();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && playerMarker.edgeMarkerContains(event.x(), event.y())) {
            centerOnPlayer();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true;
            middleDragging = false;
            dragLastMouseX = event.x();
            dragLastMouseY = event.y();
            moveVelocityX = 0.0;
            moveVelocityZ = 0.0;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            middleDragging = true;
            dragging = false;
            dragStartMouseX = event.x();
            dragStartYaw = viewYaw;
            return true;
        }
        return super.mouseClicked(event, isInside);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            middleDragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            panByPixels(event.x() - dragLastMouseX, event.y() - dragLastMouseY);
            dragLastMouseX = event.x();
            dragLastMouseY = event.y();
            syncWorldCamera();
            return true;
        }
        if (middleDragging) {
            viewYaw = dragStartYaw + (event.x() - dragStartMouseX) * 0.01;
            syncWorldCamera();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_M) {
            onClose();
            return true;
        }
        if (keyCode == InputConstants.KEY_W || keyCode == InputConstants.KEY_S ||
                keyCode == InputConstants.KEY_A || keyCode == InputConstants.KEY_D ||
                keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN ||
                keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT) {
            return true;
        }
        if (keyCode == InputConstants.KEY_C && minecraft.player != null) {
            centerOnPlayer();
            return true;
        }
        if (keyCode == InputConstants.KEY_EQUALS || keyCode == InputConstants.KEY_ADD) {
            zoomAt(lastZoomMouseX(), lastZoomMouseY(), 0.8);
            return true;
        }
        if (keyCode == InputConstants.KEY_MINUS || keyCode == SDLScancode.SDL_SCANCODE_KP_MINUS) {
            zoomAt(lastZoomMouseX(), lastZoomMouseY(), 1.25);
            return true;
        }
        return super.keyPressed(event);
    }

    private double lastZoomMouseX() {
        return Double.isNaN(lastMouseX) ? width / 2.0 : lastMouseX;
    }

    private double lastZoomMouseY() {
        return Double.isNaN(lastMouseY) ? height / 2.0 : lastMouseY;
    }

    @Override
    public void onClose() {
        closeMapView();
        super.onClose();
    }

    @Override
    public void removed() {
        closeMapView();
        if (openLogged) {
            VoxyMapClient.LOGGER.info("[VoxyMap] Map closed.");
            openLogged = false;
        }
        super.removed();
    }

    private void closeMapView() {
        restoreVoxySettings();
        VoxyMapGuiRenderer.close(minecraft);
        VoxyMapCameraController.deactivate();
        MapRenderSettingsGuard.restoreAfterMap(minecraft);
    }

    /** Before {@link VoxyMapGuiRenderer#close}: under Iris it rebuilds Voxy's renderer, which reads these settings. */
    private static void restoreVoxySettings() {
        VoxyBridge.restoreEnvironmentalFogAfterMap();
        VoxyBridge.restoreRenderDistanceAfterMap();
    }

    @Override
    public boolean isPauseScreen() {
        return VoxyMapSettings.pauseSingleplayer()
                && minecraft != null
                && minecraft.hasSingleplayerServer()
                && minecraft.isLocalServer()
                && minecraft.getSingleplayerServer() != null
                && !minecraft.getSingleplayerServer().isPublished();
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
