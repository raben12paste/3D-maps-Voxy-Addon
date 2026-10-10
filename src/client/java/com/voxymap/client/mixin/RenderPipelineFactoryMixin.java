package com.voxymap.client.mixin;

import com.voxymap.client.map.VoxyMapGuiRenderer;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderPipelineFactory;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(value = RenderPipelineFactory.class, remap = false)
public abstract class RenderPipelineFactoryMixin {
    /**
     * While the map is drawn from the GUI the Iris pipeline cannot be used, so returning
     * {@code null} here makes Voxy fall back to its normal render pipeline.
     */
    @Inject(method = "createIrisPipeline", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxymap$useGuiPipeline(RenderProperties properties, AsyncNodeManager nodeManager,
                                               NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traverser,
                                               BooleanSupplier shouldRender,
                                               CallbackInfoReturnable<AbstractRenderPipeline> cir) {
        if (VoxyMapGuiRenderer.isActive()) {
            cir.setReturnValue(null);
        }
    }
}
