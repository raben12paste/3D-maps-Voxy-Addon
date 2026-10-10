package com.voxymap.client.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import com.voxymap.client.VoxyMapClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

/**
 * Draws the Voxy LOD world straight into the main render target just before the GUI is
 * rendered. This is only used when an Iris shader pack is active, because the shader
 * pipeline cannot render the detached map camera during the normal world pass.
 */
public final class VoxyMapGuiRenderer {
    /** Minecraft uses a reversed depth buffer, so "far" is 0.0. */
    private static final double CLEAR_DEPTH = 0.0;
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.03f, 0.03f, 0.06f, 1.0f);

    private static boolean active;
    private static boolean rendering;

    private VoxyMapGuiRenderer() {
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean shouldCancelWorldRender() {
        return active && !rendering;
    }

    public static void open(Minecraft minecraft) {
        if (active || minecraft == null || minecraft.level == null) {
            return;
        }
        if (!shouldRenderInGui()) {
            return;
        }

        active = true;
        try {
            rebuildRenderer(minecraft);
        } catch (RuntimeException | LinkageError exception) {
            active = false;
            VoxyMapClient.LOGGER.error("[VoxyMap] Could not prepare Voxy GUI rendering.", exception);
            restoreRenderer(minecraft);
        }
    }

    public static void close(Minecraft minecraft) {
        if (!active) {
            return;
        }

        rendering = false;
        active = false;
        restoreRenderer(minecraft);
    }

    public static void render(Minecraft minecraft) {
        if (!active || minecraft == null || minecraft.level == null) {
            return;
        }

        VoxyRenderSystem renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }

        Viewport<?> viewport = renderer.getViewport();
        if (viewport == null) {
            return;
        }

        RenderTarget renderTarget = minecraft.gameRenderer.mainRenderTarget();
        if (renderTarget.width <= 0 || renderTarget.height <= 0) {
            return;
        }
        if (!(renderTarget.getColorTextureView() instanceof GlTextureView colorView)
                || !(renderTarget.getDepthTextureView() instanceof GlTextureView depthView)) {
            return;
        }

        int colorId = colorView.glId();
        int depthId = depthView.glId();
        if (colorId == 0 || depthId == 0) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(renderTarget.getColorTexture(), CLEAR_COLOR, renderTarget.getDepthTexture(), CLEAR_DEPTH);

        rendering = true;
        try {
            renderer.renderOpaque(viewport, depthId, colorId);
        } finally {
            rendering = false;
        }

        // The GUI is drawn on top of the map, so the depth written by Voxy has to go.
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(renderTarget.getDepthTexture(), CLEAR_DEPTH);
    }

    private static void rebuildRenderer(Minecraft minecraft) {
        IVoxyRenderSystemHolder levelRenderer = (IVoxyRenderSystemHolder) minecraft.levelRenderer;
        levelRenderer.voxy$shutdownRenderer();
        levelRenderer.voxy$createRenderer();
    }

    private static void restoreRenderer(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }

        try {
            rebuildRenderer(minecraft);
        } catch (RuntimeException | LinkageError exception) {
            VoxyMapClient.LOGGER.error("[VoxyMap] Could not restore Voxy world rendering.", exception);
        }
    }

    private static boolean shouldRenderInGui() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }

        try {
            return IrisAccess.hasActiveShaderPack();
        } catch (LinkageError exception) {
            return false;
        }
    }

    private static final class IrisAccess {
        private static boolean hasActiveShaderPack() {
            return IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
