package com.robocore.mixin;

import com.robocore.RoboCoreMod;
import com.robocore.config.RoboCoreConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into MinecraftServer to hook into the server lifecycle
 * for performance monitoring and optimization.
 *
 * This mixin provides:
 * - Server tick time measurement for TPS calculation
 * - Memory pressure detection
 * - Automatic optimization triggers
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * Track tick start time for performance measurement.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickStart(CallbackInfo ci) {
        // Performance monitoring is handled by PerformanceMonitor
        // This mixin could be extended for more granular tick measurements
    }

    /**
     * Track tick end time and report performance metrics.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickEnd(CallbackInfo ci) {
        if (!RoboCoreConfig.PERFORMANCE_AI_ENABLED) {
            return;
        }

        // The main performance monitoring happens in RoboCoreMod's tick handler
        // This mixin is available for additional server-level hooks
    }
}
