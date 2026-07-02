package com.jedrock.utils;

/**
 * Lightweight tick and time utilities.
 */
public final class TickUtil {

    public static final int TPS = 20;
    public static final long MILLIS_PER_TICK = 1000L / TPS; // 50ms

    private TickUtil() {}

    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static long nanoTime() {
        return System.nanoTime();
    }

    /**
     * Converts tick count to milliseconds (ideal).
     */
    public static long ticksToMillis(long ticks) {
        return ticks * MILLIS_PER_TICK;
    }

    /**
     * Approximate current tick from epoch or server start.
     */
    public static long millisToTicks(long millis) {
        return millis / MILLIS_PER_TICK;
    }
}
