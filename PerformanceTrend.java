package com.robocore.performance;

/**
 * Performance trend indicator for metric analysis.
 *
 * Used by PerformanceAI to determine if a metric is:
 * - INCREASING: Going up (bad for CPU/RAM, good for FPS)
 * - DECREASING: Going down (good for CPU/RAM, bad for FPS)
 * - STABLE: No significant change
 */
public enum PerformanceTrend {
    INCREASING,
    DECREASING,
    STABLE
}
