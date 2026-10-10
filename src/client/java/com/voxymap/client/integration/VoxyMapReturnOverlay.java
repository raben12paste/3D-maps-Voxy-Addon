package com.voxymap.client.integration;

import com.voxymap.client.gui.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class VoxyMapReturnOverlay {

    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_MARGIN = 12;

    private VoxyMapReturnOverlay() {
    }

    public static void render(GuiGraphicsExtractor g, int width, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        String label = Component.translatable("overlay.voxymap.return_3d").getString();
        int buttonWidth = Math.min(180, Math.max(120, client.font.width(label) + 34));
        int x = width - buttonWidth - BUTTON_MARGIN;
        int y = BUTTON_MARGIN;
        boolean hovered = contains(width, mouseX, mouseY);
        int bg = hovered ? 0xD014253B : 0xA8030914;
        int accent = hovered ? 0xFF9BEAFF : 0xFF3BA4FF;

        g.fill(x, y, x + buttonWidth, y + BUTTON_HEIGHT, bg);
        g.fill(x, y, x + 2, y + BUTTON_HEIGHT, accent);

        int iconX = x + 12;
        int iconY = y + 12;
        g.fill(iconX - 6, iconY - 3, iconX - 2, iconY + 3, 0xFF17344B);
        g.fill(iconX - 1, iconY - 6, iconX + 3, iconY, 0xFF1D5571);
        g.fill(iconX + 2, iconY - 2, iconX + 6, iconY + 5, accent);
        g.text(client.font, label, x + 28, y + 8, 0xFFE9F2FF);
    }

    public static boolean click(int width, double mouseX, double mouseY) {
        if (!contains(width, mouseX, mouseY)) {
            return false;
        }
        Minecraft.getInstance().gui.setScreen(new MapScreen());
        return true;
    }

    private static boolean contains(int width, double mouseX, double mouseY) {
        Minecraft client = Minecraft.getInstance();
        String label = Component.translatable("overlay.voxymap.return_3d").getString();
        int buttonWidth = Math.min(180, Math.max(120, client.font.width(label) + 34));
        int x = width - buttonWidth - BUTTON_MARGIN;
        int y = BUTTON_MARGIN;
        return mouseX >= x && mouseX <= x + buttonWidth
                && mouseY >= y && mouseY <= y + BUTTON_HEIGHT;
    }
}
