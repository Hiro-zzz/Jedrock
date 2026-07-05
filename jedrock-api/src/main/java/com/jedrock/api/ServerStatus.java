package com.jedrock.api;

import java.util.Locale;

/**
 * Immutable snapshot of live server health, cheap to build on demand. TPS and MSPT come from the game
 * loop; memory from the JVM {@code Runtime}; player count from the roster.
 */
public record ServerStatus(
        double tps,
        double mspt,
        double peakMspt,
        long tick,
        long uptimeMillis,
        int onlinePlayers,
        long usedMemoryBytes,
        long maxMemoryBytes) {

    /**
     * A compact one-line summary, e.g.
     * {@code TPS 20.0 | MSPT 0.42 (peak 3.10) | players 2 | mem 45/512 MB | up 12m03s}.
     */
    public String summary() {
        return String.format(Locale.ROOT,
                "TPS %.1f | MSPT %.2f (peak %.2f) | players %d | mem %d/%d MB | up %s",
                tps, mspt, peakMspt, onlinePlayers,
                usedMemoryBytes / (1024 * 1024), maxMemoryBytes / (1024 * 1024),
                formatUptime(uptimeMillis));
    }

    private static String formatUptime(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%dh%02dm", h, m);
        }
        if (m > 0) {
            return String.format("%dm%02ds", m, s);
        }
        return s + "s";
    }
}
