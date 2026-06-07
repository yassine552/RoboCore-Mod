package com.robocore.native_;

import com.robocore.RoboCoreMod;
import com.robocore.config.RoboCoreConfig;

/**
 * Native Bridge - JNI Interface to Rust Performance Engine
 *
 * This class provides the Java-side interface to the Rust native library.
 * It handles loading the native library and exposing Rust functions to Java.
 *
 * The Rust library (robocore_native) provides:
 * - High-performance terrain generation algorithms
 * - ZSTD-based data compression for world saves
 * - Efficient network packet batching and compression
 * - Thread pool management for parallel processing
 *
 * If the Rust library is not available, all methods fall back to Java implementations
 * when RoboCoreConfig.RUST_FALLBACK_TO_JAVA is true.
 */
public class NativeBridge {

    private boolean initialized = false;

    // Load the native Rust library
    static {
        try {
            System.loadLibrary(RoboCoreConfig.RUST_LIBRARY_PATH);
            RoboCoreMod.LOGGER.info("[RoboCore-Native] Rust library loaded: {}", RoboCoreConfig.RUST_LIBRARY_PATH);
        } catch (UnsatisfiedLinkError e) {
            RoboCoreMod.LOGGER.warn("[RoboCore-Native] Rust library not found. Native features disabled.");
            throw e;
        }
    }

    // ============ Native Method Declarations ============

    // --- Engine Lifecycle ---
    private native long nativeInitialize(int threadPoolSize);
    private native void nativeShutdown(long engineHandle);
    private native String nativeGetVersion();

    // --- Chunk Generation ---
    private native byte[] nativeGenerateTerrain(
            long engineHandle,
            int chunkX, int chunkZ,
            long worldSeed,
            int terrainType,
            int seaLevel,
            int baseHeight,
            float amplitude
    );
    private native byte[] nativeGenerateCaves(
            long engineHandle,
            int chunkX, int chunkZ,
            long worldSeed,
            byte[] terrainData
    );
    private native byte[] nativeGenerateOres(
            long engineHandle,
            int chunkX, int chunkZ,
            long worldSeed,
            byte[] terrainData
    );
    private native void nativeBatchGenerateTerrain(
            long engineHandle,
            int[] chunkCoords,  // [x1, z1, x2, z2, ...]
            long worldSeed,
            int terrainType,
            int seaLevel,
            int baseHeight,
            float amplitude
    );

    // --- Compression ---
    private native byte[] nativeCompress(long engineHandle, byte[] data, int level);
    private native byte[] nativeDecompress(long engineHandle, byte[] compressedData);
    private native float nativeGetCompressionRatio(long engineHandle);
    private native byte[] nativeCompressChunkData(long engineHandle, byte[] chunkData);
    private native byte[] nativeDecompressChunkData(long engineHandle, byte[] compressedChunkData);
    private native long nativeCompressRegionFile(long engineHandle, String filePath, String outputPath);

    // --- Network ---
    private native byte[] nativeBatchPackets(long engineHandle, byte[][] packets);
    private native byte[] nativeCompressPacket(long engineHandle, byte[] packetData);
    private native byte[] nativeDecompressPacket(long engineHandle, byte[] compressedData);
    private native int nativeOptimizePacketQueue(long engineHandle, byte[] queueState);
    private native long nativeCalculateLatency(long engineHandle, byte[] pingData);

    // --- Performance ---
    private native double nativeGetCPUUsage();
    private native long nativeGetMemoryUsage();
    private native int nativeGetActiveThreads(long engineHandle);

    // ============ Java-side State ============
    private long engineHandle = 0;

    /**
     * Initialize the Rust engine.
     * Creates the thread pool and prepares all subsystems.
     */
    public void initialize() {
        if (initialized) {
            RoboCoreMod.LOGGER.warn("[RoboCore-Native] Engine already initialized");
            return;
        }

        engineHandle = nativeInitialize(RoboCoreConfig.RUST_THREAD_POOL_SIZE);
        if (engineHandle == 0) {
            throw new RuntimeException("Failed to initialize Rust engine");
        }

        String version = nativeGetVersion();
        RoboCoreMod.LOGGER.info("[RoboCore-Native] Rust engine v{} initialized (handle: {})", version, engineHandle);
        initialized = true;
    }

    /**
     * Shutdown the Rust engine and release all native resources.
     */
    public void shutdown() {
        if (!initialized || engineHandle == 0) return;

        nativeShutdown(engineHandle);
        RoboCoreMod.LOGGER.info("[RoboCore-Native] Rust engine shut down");
        engineHandle = 0;
        initialized = false;
    }

    // ============ Chunk Generation API ============

    /**
     * Generate terrain data for a chunk using Rust's high-performance algorithms.
     *
     * @param chunkX      Chunk X coordinate
     * @param chunkZ      Chunk Z coordinate
     * @param worldSeed   World seed for deterministic generation
     * @param terrainType 0=Normal, 1=Flat, 2=Amplified, 3=Caves
     * @param seaLevel    Sea level height
     * @param baseHeight  Base terrain height
     * @param amplitude   Terrain amplitude factor
     * @return Serialized terrain data (heightmap + biome data + block states)
     */
    public byte[] generateTerrain(int chunkX, int chunkZ, long worldSeed,
                                   int terrainType, int seaLevel,
                                   int baseHeight, float amplitude) {
        if (!initialized) {
            return generateTerrainFallback(chunkX, chunkZ, worldSeed);
        }

        try {
            return nativeGenerateTerrain(engineHandle, chunkX, chunkZ, worldSeed,
                    terrainType, seaLevel, baseHeight, amplitude);
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Native] Terrain generation failed, using fallback", e);
            if (RoboCoreConfig.RUST_FALLBACK_TO_JAVA) {
                return generateTerrainFallback(chunkX, chunkZ, worldSeed);
            }
            throw e;
        }
    }

    /**
     * Generate cave systems for a chunk.
     */
    public byte[] generateCaves(int chunkX, int chunkZ, long worldSeed, byte[] terrainData) {
        if (!initialized) {
            return terrainData; // No cave generation in fallback mode
        }
        return nativeGenerateCaves(engineHandle, chunkX, chunkZ, worldSeed, terrainData);
    }

    /**
     * Generate ore deposits for a chunk.
     */
    public byte[] generateOres(int chunkX, int chunkZ, long worldSeed, byte[] terrainData) {
        if (!initialized) {
            return terrainData; // No ore generation in fallback mode
        }
        return nativeGenerateOres(engineHandle, chunkX, chunkZ, worldSeed, terrainData);
    }

    /**
     * Batch generate terrain for multiple chunks in parallel using Rust's thread pool.
     */
    public void batchGenerateTerrain(int[] chunkCoords, long worldSeed,
                                      int terrainType, int seaLevel,
                                      int baseHeight, float amplitude) {
        if (!initialized) return;
        nativeBatchGenerateTerrain(engineHandle, chunkCoords, worldSeed,
                terrainType, seaLevel, baseHeight, amplitude);
    }

    // ============ Compression API ============

    /**
     * Compress data using ZSTD algorithm (Rust implementation).
     *
     * @param data  Raw data to compress
     * @param level Compression level (1-22 for ZSTD, we recommend 1-9)
     * @return Compressed data
     */
    public byte[] compress(byte[] data, int level) {
        if (!initialized) {
            return compressFallback(data);
        }
        try {
            return nativeCompress(engineHandle, data, level);
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-Native] Compression failed, using fallback", e);
            if (RoboCoreConfig.RUST_FALLBACK_TO_JAVA) {
                return compressFallback(data);
            }
            throw e;
        }
    }

    /**
     * Decompress ZSTD-compressed data.
     */
    public byte[] decompress(byte[] compressedData) {
        if (!initialized) {
            return decompressFallback(compressedData);
        }
        return nativeDecompress(engineHandle, compressedData);
    }

    /**
     * Get the current compression ratio.
     */
    public float getCompressionRatio() {
        if (!initialized) return 1.0f;
        return nativeGetCompressionRatio(engineHandle);
    }

    /**
     * Compress chunk data with Minecraft-specific optimizations.
     */
    public byte[] compressChunkData(byte[] chunkData) {
        if (!initialized) {
            return compressFallback(chunkData);
        }
        return nativeCompressChunkData(engineHandle, chunkData);
    }

    /**
     * Decompress chunk data.
     */
    public byte[] decompressChunkData(byte[] compressedChunkData) {
        if (!initialized) {
            return decompressFallback(compressedChunkData);
        }
        return nativeDecompressChunkData(engineHandle, compressedChunkData);
    }

    // ============ Network API ============

    /**
     * Batch multiple network packets into a single compressed payload.
     */
    public byte[] batchPackets(byte[][] packets) {
        if (!initialized) {
            return batchPacketsFallback(packets);
        }
        return nativeBatchPackets(engineHandle, packets);
    }

    /**
     * Compress a single network packet.
     */
    public byte[] compressPacket(byte[] packetData) {
        if (!initialized) return packetData;
        return nativeCompressPacket(engineHandle, packetData);
    }

    /**
     * Decompress a single network packet.
     */
    public byte[] decompressPacket(byte[] compressedData) {
        if (!initialized) return compressedData;
        return nativeDecompressPacket(engineHandle, compressedData);
    }

    /**
     * Optimize the packet queue by removing redundant packets and reordering.
     */
    public int optimizePacketQueue(byte[] queueState) {
        if (!initialized) return 0;
        return nativeOptimizePacketQueue(engineHandle, queueState);
    }

    // ============ Performance API ============

    /**
     * Get current CPU usage percentage.
     */
    public double getCPUUsage() {
        if (!initialized) return getCpuUsageFallback();
        return nativeGetCPUUsage();
    }

    /**
     * Get current memory usage in bytes.
     */
    public long getMemoryUsage() {
        if (!initialized) return getMemoryUsageFallback();
        return nativeGetMemoryUsage();
    }

    /**
     * Get number of active threads in the Rust thread pool.
     */
    public int getActiveThreads() {
        if (!initialized) return 0;
        return nativeActiveThreads(engineHandle);
    }

    private int nativeActiveThreads(long engineHandle) {
        return nativeGetActiveThreads(engineHandle);
    }

    // ============ Fallback Implementations ============

    /**
     * Java fallback for terrain generation using Simplex noise.
     */
    private byte[] generateTerrainFallback(int chunkX, int chunkZ, long worldSeed) {
        // Simplified Java terrain generation using basic noise
        // In production, this would use Minecraft's built-in chunk generator
        java.util.Random random = new java.util.Random(worldSeed ^ ((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L));
        byte[] heightmap = new byte[256]; // 16x16 heightmap
        for (int i = 0; i < 256; i++) {
            int x = i % 16;
            int z = i / 16;
            double noise = simplifiedNoise((chunkX * 16 + x) * 0.01, (chunkZ * 16 + z) * 0.01, worldSeed);
            heightmap[i] = (byte) (64 + (int) (noise * 20));
        }
        return heightmap;
    }

    private double simplifiedNoise(double x, double z, long seed) {
        // Very basic noise function for fallback
        double value = Math.sin(x * 12.9898 + z * 78.233 + seed) * 43758.5453;
        return value - Math.floor(value) - 0.5;
    }

    /**
     * Java fallback for compression using java.util.zip.
     */
    private byte[] compressFallback(byte[] data) {
        try {
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(RoboCoreConfig.COMPRESSION_LEVEL);
            deflater.setInput(data);
            deflater.finish();

            byte[] buffer = new byte[data.length];
            int compressedSize = deflater.deflate(buffer);
            deflater.end();

            byte[] result = new byte[compressedSize];
            System.arraycopy(buffer, 0, result, 0, compressedSize);
            return result;
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore] Java compression fallback failed", e);
            return data;
        }
    }

    /**
     * Java fallback for decompression using java.util.zip.
     */
    private byte[] decompressFallback(byte[] compressedData) {
        try {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(compressedData);

            byte[] buffer = new byte[compressedData.length * 10]; // Estimate
            int decompressedSize = inflater.inflate(buffer);
            inflater.end();

            byte[] result = new byte[decompressedSize];
            System.arraycopy(buffer, 0, result, 0, decompressedSize);
            return result;
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore] Java decompression fallback failed", e);
            return compressedData;
        }
    }

    /**
     * Java fallback for packet batching.
     */
    private byte[] batchPacketsFallback(byte[][] packets) {
        // Simple concatenation with length prefixes
        int totalSize = 4; // Number of packets
        for (byte[] packet : packets) {
            totalSize += 4 + packet.length; // 4 bytes for length + data
        }

        byte[] result = new byte[totalSize];
        int offset = 0;

        // Write packet count
        result[offset++] = (byte) ((packets.length >> 24) & 0xFF);
        result[offset++] = (byte) ((packets.length >> 16) & 0xFF);
        result[offset++] = (byte) ((packets.length >> 8) & 0xFF);
        result[offset++] = (byte) (packets.length & 0xFF);

        // Write each packet with length prefix
        for (byte[] packet : packets) {
            result[offset++] = (byte) ((packet.length >> 24) & 0xFF);
            result[offset++] = (byte) ((packet.length >> 16) & 0xFF);
            result[offset++] = (byte) ((packet.length >> 8) & 0xFF);
            result[offset++] = (byte) (packet.length & 0xFF);
            System.arraycopy(packet, 0, result, offset, packet.length);
            offset += packet.length;
        }

        return result;
    }

    /**
     * Java fallback for CPU usage monitoring.
     */
    private double getCpuUsageFallback() {
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return osBean.getProcessCpuLoad() * 100.0;
    }

    /**
     * Java fallback for memory usage monitoring.
     */
    private long getMemoryUsageFallback() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    // ============ State ============

    public boolean isInitialized() {
        return initialized;
    }

    public long getEngineHandle() {
        return engineHandle;
    }
}
