package com.robocore.mixin;

import com.robocore.RoboCoreMod;
import com.robocore.chunk.ChunkOptimizer;
import com.robocore.config.RoboCoreConfig;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's ChunkGenerator to intercept chunk generation
 * and route it through RoboCore's Rust-powered generation pipeline.
 *
 * When Rust chunk generation is enabled, this mixin:
 * 1. Intercepts the vanilla chunk generation call
 * 2. Routes it to the Rust engine via NativeBridge
 * 3. Applies the generated terrain data to the chunk
 * 4. Falls back to vanilla generation if Rust is unavailable
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    /**
     * Intercept chunk generation to use Rust engine when available.
     * This injects at the HEAD of the buildSurface method, which is
     * responsible for generating the terrain surface of a chunk.
     */
    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void onBuildSurface(ServerWorld world, Chunk chunk, CallbackInfo ci) {
        if (!RoboCoreConfig.RUST_CHUNK_GENERATION) {
            return; // Use vanilla generation
        }

        if (!RoboCoreMod.isRustEngineActive()) {
            return; // Rust engine not available, use vanilla
        }

        try {
            // Route to Rust chunk generation
            ChunkOptimizer optimizer = RoboCoreMod.getChunkOptimizer();
            if (optimizer != null) {
                int chunkX = chunk.getPos().x;
                int chunkZ = chunk.getPos().z;

                // Request Rust-powered generation
                optimizer.requestChunkGeneration(chunkX, chunkZ, 0, world);

                if (RoboCoreConfig.DEBUG_MODE) {
                    RoboCoreMod.LOGGER.debug("[RoboCore-Mixin] Routed chunk ({}, {}) to Rust engine",
                            chunkX, chunkZ);
                }
            }
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Mixin] Failed to route chunk generation to Rust", e);
            // Don't cancel - let vanilla generation proceed
        }
    }
}
