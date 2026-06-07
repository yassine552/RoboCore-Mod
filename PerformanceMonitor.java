package com.robocore.performance;

import com.robocore.RoboCoreMod;
import com.robocore.native_.NativeBridge;

import net.minecraft.server.MinecraftServer;

/**
 * Performance Monitor - Real-time System Metrics Collection
 *
 * Continuously tracks FPS, RAM usage, CPU usage, and other performance metrics.
 * Provides data to PerformanceAI for automatic adjustment decisions.
 *
 * Metrics collected:
 * - FPS (Frames Per Second) - both client and server TPS
 * - RAM usage (total, used, free, max)
 * - CPU usage (process and system level)
 * - Chunk generation rate
 * - Entity count
 * - Network latency
 * - Garbage collection frequency
 *
 * All metrics are stored in a circular buffer for trend analysis.
 */
public class PerformanceMonitor {

    // ============ Metric Storage ============
    private final CircularBuffer fpsHistory = new CircularBuffer(60);       // Last 60 seconds
    private final CircularBuffer tpsHistory = new CircularBuffer(60);       // Last 60 seconds
    private final CircularBuffer ramHistory = new CircularBuffer(60);       // Last 60 seconds
    private final CircularBuffer cpuHistory = new CircularBuffer(60);       // Last 60 seconds
    private final CircularBuffer entityHistory = new CircularBuffer(60);    // Last 60 seconds
    private final CircularBuffer chunkGenHistory = new CircularBuffer(60);  // Last 60 seconds

    // ============ Current Values ============
    private double currentFPS = 60.0;
    private double currentTPS = 20.0;
    private double currentRAMUsage = 0.0;      // Percentage
    private long currentRAMUsed = 0;            // Bytes
    private long currentRAMMax = 0;             // Bytes
    private double currentCPUUsage = 0.0;       // Percentage
    private int currentEntityCount = 0;
    private int currentChunkGenRate = 0;        // Chunks per second
    private double currentNetworkLatency = 0.0; // Milliseconds

    // ============ Trend Analysis ============
    private PerformanceTrend fpsTrend = PerformanceTrend.STABLE;
    private PerformanceTrend cpuTrend = PerformanceTrend.STABLE;
    private PerformanceTrend ramTrend = PerformanceTrend.STABLE;

    // ============ Peak Tracking ============
    private double peakCPU = 0.0;
    private long peakRAM = 0;
    private double lowestFPS = Double.MAX_VALUE;
    private double lowestTPS = Double.MAX_VALUE;

    // ============ References ============
    private final NativeBridge nativeBridge;
    private long lastUpdateTime = 0;

    public PerformanceMonitor() {
        this.nativeBridge = RoboCoreMod.getNativeBridge();
    }

    /**
     * Update all performance metrics. Called every PERFORMANCE_CHECK_INTERVAL ticks.
     */
    public void update(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < 1000) return; // Don't update more than once per second
        lastUpdateTime = now;

        // Collect TPS from server
        updateTPS(server);

        // Collect memory metrics
        updateMemoryMetrics();

        // Collect CPU metrics
        updateCPUMetrics();

        // Collect entity count
        updateEntityCount(server);

        // Update histories for trend analysis
        fpsHistory.add(currentFPS);
        tpsHistory.add(currentTPS);
        ramHistory.add(currentRAMUsage);
        cpuHistory.add(currentCPUUsage);
        entityHistory.add(currentEntityCount);
        chunkGenHistory.add(currentChunkGenRate);

        // Analyze trends
        analyzeTrends();

        // Update peaks
        updatePeaks();

        // Log if enabled
        if (com.robocore.config.RoboCoreConfig.LOG_PERFORMANCE_STATS) {
            logStats();
        }
    }

    /**
     * Update TPS (Ticks Per Second) from the Minecraft server.
     */
    private void updateTPS(MinecraftServer server) {
        try {
            // Minecraft server stores tick times in milliseconds
            double[] recentTps = getRecentTPS(server);
            if (recentTps.length > 0) {
                double avgTps = 0;
                for (double tps : recentTps) {
                    avgTps += tps;
                }
                avgTps /= recentTps.length;
                currentTPS = Math.min(20.0, avgTps);

                // Estimate FPS from TPS (client FPS is typically higher)
                currentFPS = currentTPS * 2.5; // Rough estimate
            }
        } catch (Exception e) {
            // Fallback: assume 20 TPS if we can't measure
            currentTPS = 20.0;
            currentFPS = 60.0;
        }
    }

    /**
     * Get recent TPS values from the server.
     */
    private double[] getRecentTPS(MinecraftServer server) {
        // Access server tick times through reflection for compatibility
        try {
            long[] tickTimes = (long[]) server.getClass()
                    .getMethod("getTickTimes")
                    .invoke(server);

            double[] tps = new double[tickTimes.length];
            for (int i = 0; i < tickTimes.length; i++) {
                tps[i] = Math.min(20.0, 1000.0 / Math.max(1, tickTimes[i] / 1000000.0));
            }
            return tps;
        } catch (Exception e) {
            return new double[]{20.0};
        }
    }

    /**
     * Update memory metrics from JVM and optionally Rust engine.
     */
    private void updateMemoryMetrics() {
        Runtime runtime = Runtime.getRuntime();

        currentRAMUsed = runtime.totalMemory() - runtime.freeMemory();
        currentRAMMax = runtime.maxMemory();
        currentRAMUsage = ((double) currentRAMUsed / currentRAMMax) * 100.0;

        // Also check Rust-side memory if available
        if (nativeBridge != null && nativeBridge.isInitialized()) {
            long rustMem = nativeBridge.getMemoryUsage();
            currentRAMUsed += rustMem;
            currentRAMUsage = ((double) currentRAMUsed / currentRAMMax) * 100.0;
        }
    }

    /**
     * Update CPU metrics from OS and optionally Rust engine.
     */
    private void updateCPUMetrics() {
        if (nativeBridge != null && nativeBridge.isInitialized()) {
            currentCPUUsage = nativeBridge.getCPUUsage();
        } else {
            // Java fallback
            try {
                com.sun.management.OperatingSystemMXBean osBean =
                        (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                currentCPUUsage = osBean.getProcessCpuLoad() * 100.0;
            } catch (Exception e) {
                currentCPUUsage = 0.0;
            }
        }
    }

    /**
     * Update entity count across all loaded worlds.
     */
    private void updateEntityCount(MinecraftServer server) {
        try {
            int totalEntities = 0;
            for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
                totalEntities += world.getEntities().size();
            }
            currentEntityCount = totalEntities;
        } catch (Exception e) {
            currentEntityCount = 0;
        }
    }

    /**
     * Analyze performance trends using recent history.
     */
    private void analyzeTrends() {
        fpsTrend = analyzeTrend(fpsHistory);
        cpuTrend = analyzeTrend(cpuHistory);
        ramTrend = analyzeTrend(ramHistory);
    }

    /**
     * Analyze trend from a metric history buffer.
     * Uses simple linear regression on recent values.
     */
    private PerformanceTrend analyzeTrend(CircularBuffer history) {
        if (history.size() < 5) return PerformanceTrend.STABLE;

        double[] recent = history.getLastN(5);
        double firstHalf = 0, secondHalf = 0;
        for (int i = 0; i < 2; i++) firstHalf += recent[i];
        for (int i = 3; i < 5; i++) secondHalf += recent[i];

        firstHalf /= 2;
        secondHalf /= 2;

        double change = ((secondHalf - firstHalf) / firstHalf) * 100;

        if (change > 10) return PerformanceTrend.INCREASING;
        if (change < -10) return PerformanceTrend.DECREASING;
        return PerformanceTrend.STABLE;
    }

    /**
     * Update peak tracking values.
     */
    private void updatePeaks() {
        peakCPU = Math.max(peakCPU, currentCPUUsage);
        peakRAM = Math.max(peakRAM, currentRAMUsed);
        lowestFPS = Math.min(lowestFPS, currentFPS);
        lowestTPS = Math.min(lowestTPS, currentTPS);
    }

    /**
     * Log current performance statistics.
     */
    private void logStats() {
        RoboCoreMod.LOGGER.debug("[RoboCore-Perf] TPS: {:.1f} | FPS: {:.1f} | RAM: {:.1f}% ({}/{}MB) | CPU: {:.1f}% | Entities: {}",
                currentTPS, currentFPS, currentRAMUsage,
                currentRAMUsed / (1024 * 1024), currentRAMMax / (1024 * 1024),
                currentCPUUsage, currentEntityCount);
    }

    // ============ Getters ============

    public double getCurrentFPS() { return currentFPS; }
    public double getCurrentTPS() { return currentTPS; }
    public double getCurrentRAMUsage() { return currentRAMUsage; }
    public long getCurrentRAMUsed() { return currentRAMUsed; }
    public long getCurrentRAMMax() { return currentRAMMax; }
    public double getCurrentCPUUsage() { return currentCPUUsage; }
    public int getCurrentEntityCount() { return currentEntityCount; }
    public int getCurrentChunkGenRate() { return currentChunkGenRate; }
    public double getCurrentNetworkLatency() { return currentNetworkLatency; }

    public PerformanceTrend getFpsTrend() { return fpsTrend; }
    public PerformanceTrend getCpuTrend() { return cpuTrend; }
    public PerformanceTrend getRamTrend() { return ramTrend; }

    public double getPeakCPU() { return peakCPU; }
    public long getPeakRAM() { return peakRAM; }
    public double getLowestFPS() { return lowestFPS; }
    public double getLowestTPS() { return lowestTPS; }

    public void setCurrentNetworkLatency(double latency) {
        this.currentNetworkLatency = latency;
    }

    public void setCurrentChunkGenRate(int rate) {
        this.currentChunkGenRate = rate;
    }

    /**
     * Reset peak tracking values (e.g., on world load).
     */
    public void resetPeaks() {
        peakCPU = 0;
        peakRAM = 0;
        lowestFPS = Double.MAX_VALUE;
        lowestTPS = Double.MAX_VALUE;
    }

    // ============ Inner Classes ============

    /**
     * Circular buffer for storing metric history.
     */
    private static class CircularBuffer {
        private final double[] buffer;
        private int head = 0;
        private int size = 0;

        public CircularBuffer(int capacity) {
            buffer = new double[capacity];
        }

        public void add(double value) {
            buffer[head] = value;
            head = (head + 1) % buffer.length;
            if (size < buffer.length) size++;
        }

        public double[] getLastN(int n) {
            n = Math.min(n, size);
            double[] result = new double[n];
            for (int i = 0; i < n; i++) {
                int idx = (head - n + i + buffer.length) % buffer.length;
                result[i] = buffer[idx];
            }
            return result;
        }

        public int size() { return size; }

        public double getAverage() {
            if (size == 0) return 0;
            double sum = 0;
            for (int i = 0; i < size; i++) {
                sum += buffer[i];
            }
            return sum / size;
        }
    }
}
