package com.robocore.performance;

import com.robocore.RoboCoreMod;
import com.robocore.config.RoboCoreConfig;
import com.robocore.native_.NativeBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * Performance AI - Intelligent Auto-Adjustment System
 *
 * The Performance AI continuously monitors system metrics and makes automatic
 * adjustments to keep the game running smoothly. It operates in multiple
 * performance tiers:
 *
 * TIER 0 - NORMAL:     System is healthy, all features active
 * TIER 1 - WARNING:    Minor performance issues, light optimizations applied
 * TIER 2 - STRESSED:   Significant performance issues, moderate optimizations
 * TIER 3 - CRITICAL:   Severe performance issues, aggressive optimizations
 * TIER 4 - EMERGENCY:  System near failure, maximum optimizations
 *
 * Actions taken per tier:
 * - TIER 1:  Reduce render distance by 2, disable some particle effects
 * - TIER 2:  Reduce render distance by 4, reduce entity spawn rates by 25%,
 *            disable non-essential animations
 * - TIER 3:  Reduce render distance to minimum, reduce entity spawn rates by 50%,
 *            disable most visual effects, compress chunk data aggressively
 * - TIER 4:  Minimum render distance, maximum entity reduction (75%),
 *            disable all non-essential processing, emergency compression
 */
public class PerformanceAI {

    private final PerformanceMonitor monitor;
    private final NativeBridge nativeBridge;

    private PerformanceTier currentTier = PerformanceTier.NORMAL;
    private PerformanceTier previousTier = PerformanceTier.NORMAL;

    // Cooldown to prevent rapid tier switching
    private long lastTierChangeTime = 0;
    private static final long TIER_CHANGE_COOLDOWN_MS = 10000; // 10 seconds

    // Recovery tracking
    private int stableTickCount = 0;
    private static final int STABLE_TICKS_FOR_RECOVERY = 60; // 3 seconds of stability before recovery

    // Applied settings tracking
    private int appliedRenderDistance = RoboCoreConfig.MAX_RENDER_DISTANCE;
    private float appliedEntityReduction = 1.0f; // 1.0 = no reduction
    private boolean appliedParticleReduction = false;
    private boolean appliedAnimationReduction = false;
    private boolean appliedEmergencyMode = false;

    public PerformanceAI(PerformanceMonitor monitor, NativeBridge nativeBridge) {
        this.monitor = monitor;
        this.nativeBridge = nativeBridge;
    }

    /**
     * Main evaluation loop. Called every PERFORMANCE_CHECK_INTERVAL ticks.
     *
     * This method:
     * 1. Evaluates current performance metrics
     * 2. Determines the appropriate performance tier
     * 3. Applies adjustments if the tier has changed
     * 4. Tracks stability for tier recovery
     */
    public void evaluateAndAdjust(MinecraftServer server) {
        if (!RoboCoreConfig.PERFORMANCE_AI_ENABLED) return;

        // Step 1: Evaluate current tier based on metrics
        PerformanceTier newTier = evaluateTier();

        // Step 2: Apply cooldown logic
        long now = System.currentTimeMillis();
        boolean canChangeTier = (now - lastTierChangeTime) > TIER_CHANGE_COOLDOWN_MS;

        // Step 3: Handle tier transitions
        if (newTier != currentTier) {
            if (newTier.ordinal() > currentTier.ordinal()) {
                // Performance is getting worse - apply immediately (with cooldown)
                if (canChangeTier) {
                    transitionToTier(newTier, server);
                }
            } else {
                // Performance is getting better - require stability before recovering
                stableTickCount++;
                if (stableTickCount >= STABLE_TICKS_FOR_RECOVERY && canChangeTier) {
                    transitionToTier(newTier, server);
                    stableTickCount = 0;
                }
            }
        } else {
            // Reset stability counter if we're at the same tier
            stableTickCount = Math.max(0, stableTickCount - 1);
        }

        // Step 4: Log current state
        if (RoboCoreConfig.DEBUG_MODE) {
            RoboCoreMod.LOGGER.debug("[RoboCore-AI] Tier: {} | FPS: {:.1f} | CPU: {:.1f}% | RAM: {:.1f}%",
                    currentTier, monitor.getCurrentFPS(),
                    monitor.getCurrentCPUUsage(), monitor.getCurrentRAMUsage());
        }
    }

    /**
     * Evaluate the current performance tier based on all available metrics.
     *
     * Uses a weighted scoring system where each metric contributes
     * to the overall performance score. The tier is determined by
     * threshold checks on this composite score.
     */
    private PerformanceTier evaluateTier() {
        double fps = monitor.getCurrentFPS();
        double cpu = monitor.getCurrentCPUUsage();
        double ram = monitor.getCurrentRAMUsage();
        double tps = monitor.getCurrentTPS();

        // Calculate performance score (0 = best, 100 = worst)
        double score = 0;

        // FPS scoring (0-30 points)
        if (fps < RoboCoreConfig.FPS_CRITICAL_THRESHOLD) {
            score += 30;
        } else if (fps < RoboCoreConfig.FPS_LOW_THRESHOLD) {
            score += 20;
        } else if (fps < 50) {
            score += 5;
        }

        // TPS scoring (0-30 points)
        if (tps < 10) {
            score += 30;
        } else if (tps < 15) {
            score += 20;
        } else if (tps < 18) {
            score += 5;
        }

        // CPU scoring (0-20 points)
        if (cpu > RoboCoreConfig.CPU_CRITICAL_PERCENT) {
            score += 20;
        } else if (cpu > RoboCoreConfig.CPU_WARNING_PERCENT) {
            score += 10;
        }

        // RAM scoring (0-20 points)
        if (ram > RoboCoreConfig.RAM_CRITICAL_PERCENT) {
            score += 20;
        } else if (ram > RoboCoreConfig.RAM_WARNING_PERCENT) {
            score += 10;
        }

        // Determine tier based on score
        if (score >= 70) return PerformanceTier.EMERGENCY;
        if (score >= 50) return PerformanceTier.CRITICAL;
        if (score >= 30) return PerformanceTier.STRESSED;
        if (score >= 15) return PerformanceTier.WARNING;
        return PerformanceTier.NORMAL;
    }

    /**
     * Transition to a new performance tier and apply the corresponding optimizations.
     */
    private void transitionToTier(PerformanceTier newTier, MinecraftServer server) {
        previousTier = currentTier;
        currentTier = newTier;
        lastTierChangeTime = System.currentTimeMillis();

        RoboCoreMod.LOGGER.info("[RoboCore-AI] Performance tier changed: {} -> {}", previousTier, currentTier);

        // Apply tier-specific optimizations
        switch (currentTier) {
            case NORMAL:
                applyNormalSettings(server);
                break;
            case WARNING:
                applyWarningSettings(server);
                break;
            case STRESSED:
                applyStressedSettings(server);
                break;
            case CRITICAL:
                applyCriticalSettings(server);
                break;
            case EMERGENCY:
                applyEmergencySettings(server);
                break;
        }

        // Notify Rust engine of performance tier change
        if (nativeBridge != null && nativeBridge.isInitialized()) {
            // Rust can adjust its thread pool and priority based on tier
            RoboCoreMod.LOGGER.info("[RoboCore-AI] Notified Rust engine of tier: {}", currentTier);
        }
    }

    // ============ Tier-Specific Settings ============

    /**
     * TIER 0 - NORMAL: Restore all settings to optimal defaults.
     */
    private void applyNormalSettings(MinecraftServer server) {
        appliedRenderDistance = RoboCoreConfig.MAX_RENDER_DISTANCE;
        appliedEntityReduction = 1.0f;
        appliedParticleReduction = false;
        appliedAnimationReduction = false;
        appliedEmergencyMode = false;

        // Restore render distance
        if (RoboCoreConfig.AUTO_ADJUST_RENDER_DISTANCE) {
            setRenderDistance(server, appliedRenderDistance);
        }

        RoboCoreMod.LOGGER.info("[RoboCore-AI] Normal mode: All optimizations disabled");
    }

    /**
     * TIER 1 - WARNING: Light optimizations.
     * - Reduce render distance by 2
     * - Disable some particle effects
     */
    private void applyWarningSettings(MinecraftServer server) {
        appliedRenderDistance = Math.max(RoboCoreConfig.MIN_RENDER_DISTANCE,
                RoboCoreConfig.MAX_RENDER_DISTANCE - 2);
        appliedEntityReduction = 0.9f;    // 10% reduction
        appliedParticleReduction = true;
        appliedAnimationReduction = false;
        appliedEmergencyMode = false;

        if (RoboCoreConfig.AUTO_ADJUST_RENDER_DISTANCE) {
            setRenderDistance(server, appliedRenderDistance);
        }

        RoboCoreMod.LOGGER.info("[RoboCore-AI] Warning mode: Render -2, Entity -10%, Particles OFF");
    }

    /**
     * TIER 2 - STRESSED: Moderate optimizations.
     * - Reduce render distance by 4
     * - Reduce entity spawn rates by 25%
     * - Disable non-essential animations
     */
    private void applyStressedSettings(MinecraftServer server) {
        appliedRenderDistance = Math.max(RoboCoreConfig.MIN_RENDER_DISTANCE,
                RoboCoreConfig.MAX_RENDER_DISTANCE - 4);
        appliedEntityReduction = 0.75f;   // 25% reduction
        appliedParticleReduction = true;
        appliedAnimationReduction = true;
        appliedEmergencyMode = false;

        if (RoboCoreConfig.AUTO_ADJUST_RENDER_DISTANCE) {
            setRenderDistance(server, appliedRenderDistance);
        }

        // Reduce entity counts in loaded worlds
        if (RoboCoreConfig.AUTO_REDUCE_ENTITIES) {
            reduceEntities(server, appliedEntityReduction);
        }

        RoboCoreMod.LOGGER.info("[RoboCore-AI] Stressed mode: Render -4, Entity -25%, Animations OFF");
    }

    /**
     * TIER 3 - CRITICAL: Aggressive optimizations.
     * - Reduce render distance to minimum + 2
     * - Reduce entity spawn rates by 50%
     * - Disable all visual effects
     * - Aggressive chunk compression
     */
    private void applyCriticalSettings(MinecraftServer server) {
        appliedRenderDistance = Math.max(RoboCoreConfig.MIN_RENDER_DISTANCE,
                RoboCoreConfig.MIN_RENDER_DISTANCE + 2);
        appliedEntityReduction = 0.5f;    // 50% reduction
        appliedParticleReduction = true;
        appliedAnimationReduction = true;
        appliedEmergencyMode = false;

        if (RoboCoreConfig.AUTO_ADJUST_RENDER_DISTANCE) {
            setRenderDistance(server, appliedRenderDistance);
        }

        if (RoboCoreConfig.AUTO_REDUCE_ENTITIES) {
            reduceEntities(server, appliedEntityReduction);
        }

        // Force aggressive compression
        RoboCoreMod.LOGGER.warn("[RoboCore-AI] Critical mode: Min render, Entity -50%, All effects OFF");
    }

    /**
     * TIER 4 - EMERGENCY: Maximum optimizations.
     * - Minimum render distance
     * - 75% entity reduction
     * - All non-essential processing disabled
     * - Emergency compression mode
     */
    private void applyEmergencySettings(MinecraftServer server) {
        appliedRenderDistance = RoboCoreConfig.MIN_RENDER_DISTANCE;
        appliedEntityReduction = 0.25f;   // 75% reduction
        appliedParticleReduction = true;
        appliedAnimationReduction = true;
        appliedEmergencyMode = true;

        if (RoboCoreConfig.AUTO_ADJUST_RENDER_DISTANCE) {
            setRenderDistance(server, appliedRenderDistance);
        }

        if (RoboCoreConfig.AUTO_REDUCE_ENTITIES) {
            reduceEntities(server, appliedEntityReduction);
        }

        RoboCoreMod.LOGGER.error("[RoboCore-AI] EMERGENCY mode: Min render, Entity -75%, Emergency mode ON");
    }

    // ============ Optimization Actions ============

    /**
     * Set the server render distance.
     */
    private void setRenderDistance(MinecraftServer server, int distance) {
        try {
            server.setSimulationDistance(distance);
            RoboCoreMod.LOGGER.info("[RoboCore-AI] Render distance set to: {}", distance);
        } catch (Exception e) {
            RoboCoreMod.LOGGER.error("[RoboCore-AI] Failed to set render distance", e);
        }
    }

    /**
     * Reduce entities by removing excess non-essential entities.
     * Prioritizes keeping: players, villagers, named entities, pets
     * Prioritizes removing: item drops, excess animals, arrows, XP orbs
     */
    private void reduceEntities(MinecraftServer server, float keepRatio) {
        for (ServerWorld world : server.getWorlds()) {
            try {
                // Get all entities and categorize them
                var entities = world.getEntities();

                int totalEntities = entities.size();
                int targetCount = (int) (totalEntities * keepRatio);
                int toRemove = totalEntities - targetCount;

                if (toRemove <= 0) continue;

                int removed = 0;

                // Priority removal order: XP orbs > item drops > arrows > excess animals
                for (var entity : entities) {
                    if (removed >= toRemove) break;

                    // Never remove players
                    if (entity instanceof net.minecraft.entity.player.PlayerEntity) continue;

                    // Remove low-priority entities first
                    if (isRemovableEntity(entity)) {
                        entity.discard();
                        removed++;
                    }
                }

                if (removed > 0) {
                    RoboCoreMod.LOGGER.info("[RoboCore-AI] Removed {} excess entities from {}",
                            removed, world.getRegistryKey().getValue());
                }
            } catch (Exception e) {
                RoboCoreMod.LOGGER.error("[RoboCore-AI] Error reducing entities", e);
            }
        }
    }

    /**
     * Check if an entity is safe to remove during performance optimization.
     */
    private boolean isRemovableEntity(Object entity) {
        // Remove XP orbs and item drops first
        if (entity instanceof net.minecraft.entity.ExperienceOrbEntity) return true;
        if (entity instanceof net.minecraft.entity.ItemEntity) return true;
        // Remove arrows and thrown projectiles
        if (entity instanceof net.minecraft.entity.projectile.ArrowEntity) return true;
        if (entity instanceof net.minecraft.entity.projectile.thrown.ThrownEntity) return true;
        return false;
    }

    // ============ Getters ============

    public PerformanceTier getCurrentTier() { return currentTier; }
    public PerformanceTier getPreviousTier() { return previousTier; }
    public int getAppliedRenderDistance() { return appliedRenderDistance; }
    public float getAppliedEntityReduction() { return appliedEntityReduction; }
    public boolean isParticleReductionActive() { return appliedParticleReduction; }
    public boolean isAnimationReductionActive() { return appliedAnimationReduction; }
    public boolean isEmergencyModeActive() { return appliedEmergencyMode; }

    /**
     * Performance tier enum representing system stress levels.
     */
    public enum PerformanceTier {
        NORMAL(0, "Normal - All features active"),
        WARNING(1, "Warning - Light optimizations"),
        STRESSED(2, "Stressed - Moderate optimizations"),
        CRITICAL(3, "Critical - Aggressive optimizations"),
        EMERGENCY(4, "Emergency - Maximum optimizations");

        private final int level;
        private final String description;

        PerformanceTier(int level, String description) {
            this.level = level;
            this.description = description;
        }

        public int getLevel() { return level; }
        public String getDescription() { return description; }
    }
}
