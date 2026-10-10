package com.voxymap.client.integration;

import com.voxymap.client.VoxyMapClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class XaeroWorldMapBridge {

    private static final String XAERO_MOD_ID = "xaeroworldmap";

    private static Boolean available;

    private XaeroWorldMapBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = FabricLoader.getInstance().isModLoaded(XAERO_MOD_ID);
        }
        return available;
    }

    public static boolean openWorldMap(Minecraft client) {
        if (!isAvailable() || client == null || client.player == null) {
            return false;
        }

        try {
            return XaeroAccess.openWorldMap(client);
        } catch (Throwable t) {
            VoxyMapClient.LOGGER.warn("[VoxyMap] Could not open Xaero World Map from VoxyMap: {}", t.toString());
            return false;
        }
    }

    private static final class XaeroAccess {
        private static boolean openWorldMap(Minecraft client) {
            var session = xaero.map.WorldMapSession.getCurrentSession();
            if (session == null) {
                VoxyMapClient.LOGGER.warn("[VoxyMap] Xaero World Map is installed, but its current session is not ready yet.");
                return false;
            }

            xaero.map.MapProcessor mapProcessor = session.getMapProcessor();
            if (mapProcessor == null) {
                VoxyMapClient.LOGGER.warn("[VoxyMap] Xaero World Map session has no active map processor.");
                return false;
            }

            Screen screen = new xaero.map.gui.GuiMap(null, null, mapProcessor, client.player);
            client.gui.setScreen(screen);
            return true;
        }
    }
}
