package com.voxymap.client;

import com.voxymap.client.gui.MapScreen;
import com.voxymap.client.map.MapDataManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyMapClient implements ClientModInitializer {

    public static final String MOD_ID = "voxymap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping openMapKey;
    public static MapDataManager mapDataManager;
    public static volatile boolean debugLogging = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[VoxyMap] Initializing VoxyMap client...");

        // Register keybinding category and key
        KeyMapping.Category voxyMapCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main")
        );

        openMapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.voxymap.open_map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                voxyMapCategory
        ));

        // Initialize map data manager
        mapDataManager = new MapDataManager();
        registerClientCommands();

        // Tick events
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Check keybinding
            while (openMapKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new MapScreen());
                }
            }
            // Update map data
            if (client.level != null && client.player != null && !(client.screen instanceof MapScreen)) {
                mapDataManager.tick(client);
            }
        });

        // Cleanup on game stop
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (mapDataManager != null) {
                mapDataManager.close();
            }
        });

        LOGGER.info("[VoxyMap] VoxyMap initialized! Press M to open the map.");
    }

    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("3dmaps")
                        .then(ClientCommandManager.literal("debug")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Component.literal("VoxyMap debug logging is " + (debugLogging ? "ON" : "OFF") + ". Use /3dmaps debug on/off."));
                                    return 1;
                                })
                                .then(ClientCommandManager.literal("on")
                                        .executes(context -> {
                                            debugLogging = true;
                                            LOGGER.info("[VoxyMap] Debug logging enabled by client command.");
                                            context.getSource().sendFeedback(Component.literal("VoxyMap debug logging enabled."));
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("off")
                                        .executes(context -> {
                                            debugLogging = false;
                                            LOGGER.info("[VoxyMap] Debug logging disabled by client command.");
                                            context.getSource().sendFeedback(Component.literal("VoxyMap debug logging disabled."));
                                            return 1;
                                        })))
        ));
    }
}
