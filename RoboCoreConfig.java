package com.robocore.config;

import com.robocore.RoboCoreMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * RoboCore Configuration Management
 *
 * Manages all mod settings with sensible defaults and persistence.
 * Config is saved to robocore.json in the config directory.
 */
public class RoboCoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    // ============ Performance AI Settings ============
    public static boolean PERFORMANCE_AI_ENABLED = true;
    public static int FPS_LOW_THRESHOLD = 30;
    public static int FPS_CRITICAL_THRESHOLD = 15;
    public static double RAM_WARNING_PERCENT = 80.0;
    public static double RAM_CRITICAL_PERCENT = 92.0;
    public static double CPU_WARNING_PERCENT = 75.0;
    public static double CPU_CRITICAL_PERCENT = 90.0;
    public static boolean AUTO_ADJUST_RENDER_DISTANCE = true;
    public static int MIN_RENDER_DISTANCE = 4;
    public static int MAX_RENDER_DISTANCE = 32;
    public static boolean AUTO_REDUCE_ENTITIES = true;

    // ============ Chunk Optimization Settings ============
    public static boolean RUST_CHUNK_GENERATION = true;
    public static int CHUNK_GEN_BATCH_SIZE = 4;
    public static boolean CHUNK_ASYNC_GENERATION = true;
    public static int CHUNK_CACHE_SIZE = 256;
    public static boolean CHUNK_DEFRAGMENTATION = true;

    // ============ Compression Settings ============
    public static boolean COMPRESSION_ENABLED = true;
    public static int COMPRESSION_LEVEL = 6;            // 1-9 (higher = more compression, slower)
    public static int COMPRESSION_INTERVAL_TICKS = 6000; // 5 minutes
    public static boolean COMPRESSION_CHUNK_DATA = true;
    public static boolean COMPRESSION_REGION_FILES = true;
    public static double COMPRESSION_TARGET_RATIO = 0.6; // Target 60% of original size

    // ============ Network Optimization Settings ============
    public static boolean NETWORK_OPTIMIZATION_ENABLED = true;
    public static int PACKET_BATCH_INTERVAL_MS = 50;     // Batch packets every 50ms
    public static int MAX_PACKETS_PER_BATCH = 64;
    public static boolean PACKET_COMPRESSION = true;
    public static int PACKET_COMPRESSION_THRESHOLD = 256; // Compress packets > 256 bytes
    public static boolean LAG_COMPENSATION = true;
    public static int LAG_SPIKE_THRESHOLD_MS = 200;

    // ============ Rust Engine Settings ============
    public static String RUST_LIBRARY_PATH = "robocore_native";
    public static int RUST_THREAD_POOL_SIZE = 4;
    public static boolean RUST_FALLBACK_TO_JAVA = true;

    // ============ Debug Settings ============
    public static boolean DEBUG_MODE = false;
    public static boolean LOG_PERFORMANCE_STATS = true;
    public static int LOG_PERFORMANCE_INTERVAL_TICKS = 200; // Every 10 seconds

    /**
     * Load configuration from file, creating defaults if not found.
     */
    public static void load() {
        configFile = new File("config/robocore.json");

        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                fromJson(json);
                RoboCoreMod.LOGGER.info("[RoboCore] Configuration loaded from file");
            } catch (Exception e) {
                RoboCoreMod.LOGGER.warn("[RoboCore] Failed to load config, using defaults", e);
                save(); // Create default config file
            }
        } else {
            RoboCoreMod.LOGGER.info("[RoboCore] No config file found, creating default configuration");
            configFile.getParentFile().mkdirs();
            save();
        }
    }

    /**
     * Save current configuration to file.
     */
    public static void save() {
        if (configFile == null) {
            configFile = new File("config/robocore.json");
            configFile.getParentFile().mkdirs();
        }

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(toJson(), writer);
        } catch (IOException e) {
            RoboCoreMod.LOGGER.error("[RoboCore] Failed to save config", e);
        }
    }

    /**
     * Serialize configuration to JSON.
     */
    private static JsonObject toJson() {
        JsonObject root = new JsonObject();

        JsonObject performance = new JsonObject();
        performance.addProperty("enabled", PERFORMANCE_AI_ENABLED);
        performance.addProperty("fps_low_threshold", FPS_LOW_THRESHOLD);
        performance.addProperty("fps_critical_threshold", FPS_CRITICAL_THRESHOLD);
        performance.addProperty("ram_warning_percent", RAM_WARNING_PERCENT);
        performance.addProperty("ram_critical_percent", RAM_CRITICAL_PERCENT);
        performance.addProperty("cpu_warning_percent", CPU_WARNING_PERCENT);
        performance.addProperty("cpu_critical_percent", CPU_CRITICAL_PERCENT);
        performance.addProperty("auto_adjust_render_distance", AUTO_ADJUST_RENDER_DISTANCE);
        performance.addProperty("min_render_distance", MIN_RENDER_DISTANCE);
        performance.addProperty("max_render_distance", MAX_RENDER_DISTANCE);
        performance.addProperty("auto_reduce_entities", AUTO_REDUCE_ENTITIES);
        root.add("performance_ai", performance);

        JsonObject chunk = new JsonObject();
        chunk.addProperty("rust_chunk_generation", RUST_CHUNK_GENERATION);
        chunk.addProperty("gen_batch_size", CHUNK_GEN_BATCH_SIZE);
        chunk.addProperty("async_generation", CHUNK_ASYNC_GENERATION);
        chunk.addProperty("cache_size", CHUNK_CACHE_SIZE);
        chunk.addProperty("defragmentation", CHUNK_DEFRAGMENTATION);
        root.add("chunk_optimization", chunk);

        JsonObject compression = new JsonObject();
        compression.addProperty("enabled", COMPRESSION_ENABLED);
        compression.addProperty("level", COMPRESSION_LEVEL);
        compression.addProperty("interval_ticks", COMPRESSION_INTERVAL_TICKS);
        compression.addProperty("chunk_data", COMPRESSION_CHUNK_DATA);
        compression.addProperty("region_files", COMPRESSION_REGION_FILES);
        compression.addProperty("target_ratio", COMPRESSION_TARGET_RATIO);
        root.add("compression", compression);

        JsonObject network = new JsonObject();
        network.addProperty("enabled", NETWORK_OPTIMIZATION_ENABLED);
        network.addProperty("batch_interval_ms", PACKET_BATCH_INTERVAL_MS);
        network.addProperty("max_packets_per_batch", MAX_PACKETS_PER_BATCH);
        network.addProperty("compression", PACKET_COMPRESSION);
        network.addProperty("compression_threshold", PACKET_COMPRESSION_THRESHOLD);
        network.addProperty("lag_compensation", LAG_COMPENSATION);
        network.addProperty("lag_spike_threshold_ms", LAG_SPIKE_THRESHOLD_MS);
        root.add("network_optimization", network);

        JsonObject rust = new JsonObject();
        rust.addProperty("library_path", RUST_LIBRARY_PATH);
        rust.addProperty("thread_pool_size", RUST_THREAD_POOL_SIZE);
        rust.addProperty("fallback_to_java", RUST_FALLBACK_TO_JAVA);
        root.add("rust_engine", rust);

        JsonObject debug = new JsonObject();
        debug.addProperty("debug_mode", DEBUG_MODE);
        debug.addProperty("log_performance_stats", LOG_PERFORMANCE_STATS);
        debug.addProperty("log_performance_interval_ticks", LOG_PERFORMANCE_INTERVAL_TICKS);
        root.add("debug", debug);

        return root;
    }

    /**
     * Deserialize configuration from JSON.
     */
    private static void fromJson(JsonObject root) {
        if (root.has("performance_ai")) {
            JsonObject p = root.getAsJsonObject("performance_ai");
            PERFORMANCE_AI_ENABLED = p.get("enabled").getAsBoolean();
            FPS_LOW_THRESHOLD = p.get("fps_low_threshold").getAsInt();
            FPS_CRITICAL_THRESHOLD = p.get("fps_critical_threshold").getAsInt();
            RAM_WARNING_PERCENT = p.get("ram_warning_percent").getAsDouble();
            RAM_CRITICAL_PERCENT = p.get("ram_critical_percent").getAsDouble();
            CPU_WARNING_PERCENT = p.get("cpu_warning_percent").getAsDouble();
            CPU_CRITICAL_PERCENT = p.get("cpu_critical_percent").getAsDouble();
            AUTO_ADJUST_RENDER_DISTANCE = p.get("auto_adjust_render_distance").getAsBoolean();
            MIN_RENDER_DISTANCE = p.get("min_render_distance").getAsInt();
            MAX_RENDER_DISTANCE = p.get("max_render_distance").getAsInt();
            AUTO_REDUCE_ENTITIES = p.get("auto_reduce_entities").getAsBoolean();
        }

        if (root.has("chunk_optimization")) {
            JsonObject c = root.getAsJsonObject("chunk_optimization");
            RUST_CHUNK_GENERATION = c.get("rust_chunk_generation").getAsBoolean();
            CHUNK_GEN_BATCH_SIZE = c.get("gen_batch_size").getAsInt();
            CHUNK_ASYNC_GENERATION = c.get("async_generation").getAsBoolean();
            CHUNK_CACHE_SIZE = c.get("cache_size").getAsInt();
            CHUNK_DEFRAGMENTATION = c.get("defragmentation").getAsBoolean();
        }

        if (root.has("compression")) {
            JsonObject c = root.getAsJsonObject("compression");
            COMPRESSION_ENABLED = c.get("enabled").getAsBoolean();
            COMPRESSION_LEVEL = c.get("level").getAsInt();
            COMPRESSION_INTERVAL_TICKS = c.get("interval_ticks").getAsInt();
            COMPRESSION_CHUNK_DATA = c.get("chunk_data").getAsBoolean();
            COMPRESSION_REGION_FILES = c.get("region_files").getAsBoolean();
            COMPRESSION_TARGET_RATIO = c.get("target_ratio").getAsDouble();
        }

        if (root.has("network_optimization")) {
            JsonObject n = root.getAsJsonObject("network_optimization");
            NETWORK_OPTIMIZATION_ENABLED = n.get("enabled").getAsBoolean();
            PACKET_BATCH_INTERVAL_MS = n.get("batch_interval_ms").getAsInt();
            MAX_PACKETS_PER_BATCH = n.get("max_packets_per_batch").getAsInt();
            PACKET_COMPRESSION = n.get("compression").getAsBoolean();
            PACKET_COMPRESSION_THRESHOLD = n.get("compression_threshold").getAsInt();
            LAG_COMPENSATION = n.get("lag_compensation").getAsBoolean();
            LAG_SPIKE_THRESHOLD_MS = n.get("lag_spike_threshold_ms").getAsInt();
        }

        if (root.has("rust_engine")) {
            JsonObject r = root.getAsJsonObject("rust_engine");
            RUST_LIBRARY_PATH = r.get("library_path").getAsString();
            RUST_THREAD_POOL_SIZE = r.get("thread_pool_size").getAsInt();
            RUST_FALLBACK_TO_JAVA = r.get("fallback_to_java").getAsBoolean();
        }

        if (root.has("debug")) {
            JsonObject d = root.getAsJsonObject("debug");
            DEBUG_MODE = d.get("debug_mode").getAsBoolean();
            LOG_PERFORMANCE_STATS = d.get("log_performance_stats").getAsBoolean();
            LOG_PERFORMANCE_INTERVAL_TICKS = d.get("log_performance_interval_ticks").getAsInt();
        }
    }
}
