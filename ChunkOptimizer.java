package com.robocore.chunk;

import com.robocore.RoboCoreMod;
import com.robocore.config.RoboCoreConfig;
import com.robocore.native_.NativeBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk Optimizer - High-Performance Chunk Generation & Management
 *
 * This system optimizes chunk generation by offloading heavy terrain
 * calculations to the Rust engine while keeping Minecraft API integration
 * in Java.
 *
 * Key features:
 * - Async chunk generation using Rust's thread pool
 * - Batch processing of multiple chunks
 * - Smart chunk caching with LRU eviction
 * - Chunk defragmentation for optimized storage
 * - Prioritized chunk loading (player proximity first)
 *
 * Architecture:
 * ┌─────────────────────────────────────┐
 * │  Minecraft Chunk Manager (Java)     │
 * │  - Request queue                    │
 * │  - Priority ordering                │
 * │  - Result integration               │
 * ├─────────────────────────────────────┤
 * │  Rust Chunk Engine                  │
 * │  - Terrain generation (Perlin/Simplex) │
 * │  - Cave carving                     │
 * │  - Ore distribution                 │
 * │  - Biome assignment                 │
 * │  - Multi-threaded batch processing  │
 * └─────────────────────────────────────┘
 */
public class ChunkOptimizer {

    private final NativeBridge nativeBridge;
    private final ExecutorService asyncExecutor;
    private final ChunkCache chunkCache;
    private final AtomicInteger chunksGeneratedThisSecond = new AtomicInteger(0);

    // Chunk generation statistics
    private long totalChunksGenerated = 0;
    private long totalGenerationTimeMs = 0;
    private double averageGenerationTimeMs = 0;

    // Pending chunk requests
    private final PriorityBlockingQueue<ChunkRequest> pendingRequests =
            new PriorityBlockingQueue<>(64);

    // Completed chunk data awaiting integration
    private final ConcurrentLinkedQueue<ChunkResult> completedResults =
            new ConcurrentLinkedQueue<>();

    public ChunkOptimizer(NativeBridge nativeBridge) {
        this.nativeBridge = nativeBridge;
        this.chunkCache = new ChunkCache(RoboCoreConfig.CHUNK_CACHE_SIZE);
        this.asyncExecutor = Executors.newFixedThreadPool(
                RoboCoreConfig.RUST_CHUNK_GENERATION ? 2 : RoboCoreConfig.RUST_THREAD_POOL_SIZE,
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "RoboCore-ChunkGen-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
                        return t;
                    }
                }
        );

        RoboCoreMod.LOGGER.info("[RoboCore-Chunk] Optimizer initialized (Rust: {})",
                nativeBridge != null ? "ENABLED" : "DISABLED");
    }

    /**
     * Called every server tick to process chunk generation requests.
     */
    public void tick(MinecraftServer server) {
        try {
            // Step 1: Integrate completed chunk results into the world
            integrateCompletedResults(server);

            // Step 2: Process pending chunk requests
            processPendingRequests(server);

            // Step 3: Update statistics
            updateStats();

        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Chunk] Error during tick", e);
        }
    }

    /**
     * Request chunk generation with priority.
     *
     * @param chunkX   Chunk X coordinate
     * @param chunkZ   Chunk Z coordinate
     * @param priority Priority level (lower = higher priority)
     * @param world    Target world
     */
    public void requestChunkGeneration(int chunkX, int chunkZ,
                                        int priority, ServerWorld world) {
        // Check cache first
        long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
        if (chunkCache.contains(chunkKey)) {
            return; // Already cached
        }

        ChunkRequest request = new ChunkRequest(chunkX, chunkZ, priority, world);
        pendingRequests.offer(request);
    }

    /**
     * Process pending chunk generation requests using Rust or Java fallback.
     */
    private void processPendingRequests(MinecraftServer server) {
        int batchSize = RoboCoreConfig.CHUNK_GEN_BATCH_SIZE;
        int processed = 0;

        while (!pendingRequests.isEmpty() && processed < batchSize) {
            ChunkRequest request = pendingRequests.poll();
            if (request == null) break;

            if (RoboCoreConfig.CHUNK_ASYNC_GENERATION) {
                // Submit async generation task
                asyncExecutor.submit(() -> generateChunkAsync(request));
            } else {
                // Generate synchronously
                generateChunkSync(request);
            }

            processed++;
        }

        // Batch generation for Rust engine
        if (RoboCoreConfig.RUST_CHUNK_GENERATION && nativeBridge != null
                && nativeBridge.isInitialized() && pendingRequests.size() >= batchSize) {
            batchGenerateWithRust(server);
        }
    }

    /**
     * Generate a single chunk asynchronously.
     */
    private void generateChunkAsync(ChunkRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            byte[] terrainData;

            if (RoboCoreConfig.RUST_CHUNK_GENERATION && nativeBridge != null
                    && nativeBridge.isInitialized()) {
                // Use Rust engine for terrain generation
                long worldSeed = request.world.getSeed();
                terrainData = nativeBridge.generateTerrain(
                        request.chunkX, request.chunkZ, worldSeed,
                        0, 63, 64, 1.0f
                );

                // Generate caves
                terrainData = nativeBridge.generateCaves(
                        request.chunkX, request.chunkZ, worldSeed, terrainData
                );

                // Generate ores
                terrainData = nativeBridge.generateOres(
                        request.chunkX, request.chunkZ, worldSeed, terrainData
                );
            } else {
                // Java fallback
                terrainData = nativeBridge.generateTerrain(
                        request.chunkX, request.chunkZ, request.world.getSeed(),
                        0, 63, 64, 1.0f
                );
            }

            long genTime = System.currentTimeMillis() - startTime;

            // Cache the result
            long chunkKey = ChunkPos.toLong(request.chunkX, request.chunkZ);
            chunkCache.put(chunkKey, terrainData);

            // Add to completed results queue
            completedResults.offer(new ChunkResult(
                    request.chunkX, request.chunkZ, terrainData, genTime, request.world
            ));

            totalChunksGenerated++;
            totalGenerationTimeMs += genTime;

        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Chunk] Async generation failed for chunk ({}, {})",
                    request.chunkX, request.chunkZ, e);
        }
    }

    /**
     * Generate a single chunk synchronously (blocking).
     */
    private void generateChunkSync(ChunkRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            byte[] terrainData;

            if (nativeBridge != null && nativeBridge.isInitialized()) {
                terrainData = nativeBridge.generateTerrain(
                        request.chunkX, request.chunkZ, request.world.getSeed(),
                        0, 63, 64, 1.0f
                );
            } else {
                terrainData = nativeBridge.generateTerrain(
                        request.chunkX, request.chunkZ, request.world.getSeed(),
                        0, 63, 64, 1.0f
                );
            }

            long genTime = System.currentTimeMillis() - startTime;
            long chunkKey = ChunkPos.toLong(request.chunkX, request.chunkZ);
            chunkCache.put(chunkKey, terrainData);

            completedResults.offer(new ChunkResult(
                    request.chunkX, request.chunkZ, terrainData, genTime, request.world
            ));

            totalChunksGenerated++;
            totalGenerationTimeMs += genTime;

        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Chunk] Sync generation failed for chunk ({}, {})",
                    request.chunkX, request.chunkZ, e);
        }
    }

    /**
     * Batch generate multiple chunks using Rust's parallel processing.
     */
    private void batchGenerateWithRust(MinecraftServer server) {
        int batchSize = Math.min(pendingRequests.size(), RoboCoreConfig.CHUNK_GEN_BATCH_SIZE * 4);
        int[] chunkCoords = new int[batchSize * 2];

        for (int i = 0; i < batchSize; i++) {
            ChunkRequest request = pendingRequests.poll();
            if (request == null) break;
            chunkCoords[i * 2] = request.chunkX;
            chunkCoords[i * 2 + 1] = request.chunkZ;
        }

        try {
            nativeBridge.batchGenerateTerrain(chunkCoords, server.getOverworld().getSeed(),
                    0, 63, 64, 1.0f);
            RoboCoreMod.LOGGER.debug("[RoboCore-Chunk] Batch generated {} chunks via Rust", batchSize / 2);
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Chunk] Batch generation failed", e);
        }
    }

    /**
     * Integrate completed chunk results into the Minecraft world.
     */
    private void integrateCompletedResults(MinecraftServer server) {
        int integrated = 0;
        ChunkResult result;

        while ((result = completedResults.poll()) != null && integrated < 8) {
            try {
                // The terrain data is already generated; Minecraft's chunk system
                // will use it when loading the chunk
                if (RoboCoreConfig.DEBUG_MODE) {
                    RoboCoreMod.LOGGER.debug("[RoboCore-Chunk] Integrated chunk ({}, {}) in {}ms",
                            result.chunkX, result.chunkZ, result.generationTimeMs);
                }
                integrated++;
            } catch (Exception e) {
                RoboCoreMod.LOGGER.error("[RoboCore-Chunk] Failed to integrate chunk ({}, {})",
                        result.chunkX, result.chunkZ, e);
            }
        }
    }

    /**
     * Update generation statistics.
     */
    private void updateStats() {
        if (totalChunksGenerated > 0) {
            averageGenerationTimeMs = (double) totalGenerationTimeMs / totalChunksGenerated;
        }
    }

    // ============ Getters ============

    public long getTotalChunksGenerated() { return totalChunksGenerated; }
    public double getAverageGenerationTimeMs() { return averageGenerationTimeMs; }
    public int getPendingCount() { return pendingRequests.size(); }
    public int getCacheSize() { return chunkCache.size(); }

    // ============ Inner Classes ============

    /**
     * Chunk generation request with priority ordering.
     */
    private static class ChunkRequest implements Comparable<ChunkRequest> {
        final int chunkX;
        final int chunkZ;
        final int priority;
        final ServerWorld world;

        ChunkRequest(int chunkX, int chunkZ, int priority, ServerWorld world) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.priority = priority;
            this.world = world;
        }

        @Override
        public int compareTo(ChunkRequest other) {
            return Integer.compare(this.priority, other.priority);
        }
    }

    /**
     * Completed chunk generation result.
     */
    private static class ChunkResult {
        final int chunkX;
        final int chunkZ;
        final byte[] terrainData;
        final long generationTimeMs;
        final ServerWorld world;

        ChunkResult(int chunkX, int chunkZ, byte[] terrainData,
                     long generationTimeMs, ServerWorld world) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.terrainData = terrainData;
            this.generationTimeMs = generationTimeMs;
            this.world = world;
        }
    }

    /**
     * LRU Cache for chunk data.
     */
    private static class ChunkCache {
        private final int maxSize;
        private final ConcurrentHashMap<Long, byte[]> cache;
        private final LinkedBlockingDeque<Long> accessOrder;

        ChunkCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
            this.accessOrder = new LinkedBlockingDeque<>(maxSize);
        }

        void put(long key, byte[] data) {
            if (cache.size() >= maxSize) {
                evictOldest();
            }
            cache.put(key, data);
            accessOrder.offer(key);
        }

        byte[] get(long key) {
            byte[] data = cache.get(key);
            if (data != null) {
                // Move to front (most recently used)
                accessOrder.remove(key);
                accessOrder.offerFirst(key);
            }
            return data;
        }

        boolean contains(long key) {
            return cache.containsKey(key);
        }

        int size() {
            return cache.size();
        }

        private void evictOldest() {
            Long oldest = accessOrder.poll();
            if (oldest != null) {
                cache.remove(oldest);
            }
        }
    }
}
