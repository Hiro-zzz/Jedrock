package com.jedrock.gameloop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TPS / MSPT math for {@link TickMetrics}, driven with synthetic tick timings. */
class TickMetricsTest {

    private static final long MS = 1_000_000L; // one millisecond in nanoseconds

    @Test
    void healthyLoopReportsTargetTpsAndTickCost() {
        TickMetrics metrics = new TickMetrics();
        long start = 1_000_000_000L;
        for (int i = 0; i < 40; i++) {
            metrics.record(start + i * 50 * MS, 2 * MS); // ticks 50 ms apart, 2 ms of work each
        }
        assertEquals(20.0, metrics.tps(), 0.05, "50 ms spacing is the target 20 TPS");
        assertEquals(2.0, metrics.mspt(), 0.001, "2 ms tick work => 2.0 MSPT");
        assertEquals(40, metrics.totalTicks());
    }

    @Test
    void overloadedLoopReportsLowerTpsAndTracksPeak() {
        TickMetrics metrics = new TickMetrics();
        long start = 5_000_000_000L;
        for (int i = 0; i < 30; i++) {
            metrics.record(start + i * 100 * MS, 60 * MS); // 60 ms ticks, forced 100 ms apart => 10 TPS
        }
        assertEquals(10.0, metrics.tps(), 0.05, "100 ms spacing => 10 TPS");
        assertTrue(metrics.mspt() > 50.0, "MSPT reflects the heavy ticks");
        assertEquals(60.0, metrics.peakMspt(), 0.001, "peak captures the worst tick");
    }

    @Test
    void tpsIsCappedAtTheTargetEvenWhenTicksRunFast() {
        TickMetrics metrics = new TickMetrics();
        long start = 0L;
        for (int i = 0; i < 30; i++) {
            metrics.record(start + i * 10 * MS, MS); // 10 ms apart would be 100 TPS — must clamp to 20
        }
        assertEquals(20.0, metrics.tps(), 0.001, "a fast loop still sleeps to the target, so TPS caps at 20");
    }
}
