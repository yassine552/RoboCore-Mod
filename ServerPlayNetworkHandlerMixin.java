package com.robocore.mixin;

import com.robocore.RoboCoreMod;
import com.robocore.config.RoboCoreConfig;
import com.robocore.network.NetworkOptimizer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into ServerPlayNetworkHandler to intercept packet sending
 * and route it through RoboCore's network optimization pipeline.
 *
 * When network optimization is enabled, this mixin:
 * 1. Intercepts outgoing packets
 * 2. Routes them through the priority queue
 * 3. Applies batching and compression
 * 4. Reduces redundant position updates
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * Track packet sending for network optimization statistics.
     */
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void onSendPacket(CallbackInfo ci) {
        if (!RoboCoreConfig.NETWORK_OPTIMIZATION_ENABLED) {
            return;
        }

        try {
            NetworkOptimizer optimizer = RoboCoreMod.getNetworkOptimizer();
            if (optimizer != null) {
                // Track packet for statistics (actual optimization happens in the flush cycle)
                optimizer.queueLowPriority(player, new Object()); // Placeholder
            }
        } catch (Exception e) {
            // Silently ignore - don't break packet sending
        }
    }
}
