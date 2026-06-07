package com.robocore;

import com.robocore.config.RoboCoreConfig;
import com.robocore.performance.PerformanceMonitor;
import com.robocore.performance.PerformanceAI;
import com.robocore.chunk.ChunkOptimizer;
import com.robocore.chunk.RustChunkGenerator;
import com.robocore.compression.CompressionManager;
import com.robocore.network.NetworkOptimizer;
import com.robocore.native_.NativeBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RoboCore Mod - Main Entry Point
 *
 * A hybrid Java + Rust performance mod for Minecraft.
 *
 * Java handles: Mod lifecycle, Minecraft API integration, UI, event system
 * Rust handles: Heavy computations (chunk generation, compression, network packet processing)
 *
 * Architecture:
 * ┌──────────────────────────────────────────────┐
 * │              Minecraft Engine                  │
 * ├──────────────────────────────────────────────┤
 * │           RoboCore Mod (Java)                 │
 * │  ┌──────────┐ ┌──────────┐ ┌──────────────┐ │
 * │  │Perf AI   │ │Chunk Opt │ │Net Optimizer │ │
 * │  └────┬─────┘ └────┬─────┘ └──────┬───────┘ │
 * │       │            │              │          │
 * │  ┌────┴────────────┴──────────────┴───────┐  │
 * │  │         NativeBridge (JNI)              │  │
 * │  └────────────────┬───────────────────────┘  │
 * ├───────────────────┼──────────────────────────┤
 * │       Rust Core   │   (Performance Engine)    │
 * │  ┌────────────────┴───────────────────────┐  │
 * │  │ chunk_gen | compression | network_opt  │  │
 * │  └────────────────────────────────────────┘  │
 * └──────────────────────────────────────────────┘
 */
public class RoboCoreMod implements ModInitializer {

    public static final String MOD_ID = "robocore";
    public static final String MOD_NAME = "RoboCore";
    public static final String MOD_VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    // Core subsystems
    private static NativeBridge nativeBridge;
    private static PerformanceMonitor performanceMonitor;
    private static PerformanceAI performanceAI;
    private static ChunkOptimizer chunkOptimizer;
    private static CompressionManager compressionManager;
    private static NetworkOptimizer networkOptimizer;

    // Tick counter for periodic tasks
    private int tickCounter = 0;
    private static final int PERFORMANCE_CHECK_INTERVAL = 20;  // Every second (20 ticks)
    private static final int COMPRESSION_INTERVAL = 6000;      // Every 5 minutes
    private static final int NETWORK_OPTIMIZE_INTERVAL = 100;  // Every 5 seconds

    @Override
    public void onInitialize() {
        LOGGER.info("========================================");
        LOGGER.info("  RoboCore Mod v{} - Initializing", MOD_VERSION);
        LOGGER.info("  Java + Rust Performance Engine");
        LOGGER.info("========================================");

        // Step 1: Load configuration
        RoboCoreConfig.load();
        LOGGER.info("[RoboCore] Configuration loaded");

        // Step 2: Initialize Rust native bridge
        try {
            nativeBridge = new NativeBridge();
            nativeBridge.initialize();
            LOGGER.info("[RoboCore] Rust native engine loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[RoboCore] Failed to load Rust native library! Falling back to Java-only mode.", e);
            LOGGER.error("[RoboCore] Some features will be disabled. Please compile the Rust core.");
            nativeBridge = null;
        }

        // Step 3: Initialize performance monitoring
        performanceMonitor = new PerformanceMonitor();
        performanceAI = new PerformanceAI(performanceMonitor, nativeBridge);
        LOGGER.info("[RoboCore] Performance AI system initialized");

        // Step 4: Initialize chunk optimizer
        chunkOptimizer = new ChunkOptimizer(nativeBridge);
        LOGGER.info("[RoboCore] Chunk optimizer initialized");

        // Step 5: Initialize compression manager
        compressionManager = new CompressionManager(nativeBridge);
        LOGGER.info("[RoboCore] Compression manager initialized");

        // Step 6: Initialize network optimizer
        networkOptimizer = new NetworkOptimizer(nativeBridge);
        LOGGER.info("[RoboCore] Network optimizer initialized");

        // Register server tick event for periodic tasks
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            onServerTick(server, tickCounter);
        });

        // Register server shutdown event for cleanup
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            onShutdown();
        });

        LOGGER.info("========================================");
        LOGGER.info("  RoboCore Mod - Ready!");
        LOGGER.info("  Rust Engine: {}", nativeBridge != null ? "ACTIVE" : "FALLBACK (Java-only)");
        LOGGER.info("========================================");
    }

    /**
     * Called every server tick to manage periodic optimization tasks.
     */
    private void onServerTick(net.minecraft.server.MinecraftServer server, int tick) {
        try {
            // Performance monitoring & AI adjustment every second
            if (tick % PERFORMANCE_CHECK_INTERVAL == 0) {
                performanceMonitor.update(server);
                performanceAI.evaluateAndAdjust(server);
            }

            // Compression optimization every 5 minutes
            if (tick % COMPRESSION_INTERVAL == 0) {
                compressionManager.optimizeWorldData(server);
            }

            // Network optimization every 5 seconds
            if (tick % NETWORK_OPTIMIZE_INTERVAL == 0) {
                networkOptimizer.optimize(server);
            }

            // Chunk optimization runs every tick for smooth generation
            chunkOptimizer.tick(server);

        } catch (Exception e) {
            LOGGER.error("[RoboCore] Error during tick processing", e);
        }
    }

    /**
     * Called when the server is shutting down to cleanup resources.
     */
    private void onShutdown() {
        LOGGER.info("[RoboCore] Shutting down...");

        // Flush any pending compression tasks
        if (compressionManager != null) {
            compressionManager.flush();
        }

        // Shutdown network optimizer
        if (networkOptimizer != null) {
            networkOptimizer.shutdown();
        }

        // Cleanup Rust native resources
        if (nativeBridge != null) {
            nativeBridge.shutdown();
        }

        LOGGER.info("[RoboCore] Shutdown complete. Goodbye!");
    }

    // ============ Static Accessors ============

    public static NativeBridge getNativeBridge() {
        return nativeBridge;
    }

    public static PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public static PerformanceAI getPerformanceAI() {
        return performanceAI;
    }

    public static ChunkOptimizer getChunkOptimizer() {
        return chunkOptimizer;
    }

    public static CompressionManager getCompressionManager() {
        return compressionManager;
    }

    public static NetworkOptimizer getNetworkOptimizer() {
        return networkOptimizer;
    }

    /**
     * Check if the Rust native engine is available.
     */
    public static boolean isRustEngineActive() {
        return nativeBridge != null && nativeBridge.isInitialized();
    }
}
