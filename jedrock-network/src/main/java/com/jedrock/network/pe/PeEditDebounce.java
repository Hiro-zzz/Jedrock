package com.jedrock.network.pe;

/**
 * Collapses the retail 1.1.5 client's edit double-fire down to a single edit. The protocol-113 client
 * (unlike 0.14, which fires once) reports one physical action as a burst of packets; this de-duplicates
 * them by target cell and time. It is used two ways, because place and break have different burst shapes:
 *
 * <ul>
 *   <li><b>Placement</b> — one click emits an exact repeat of the clicked cell <em>and/or</em> a
 *       "staircase" of cells offset one further each time (the client re-runs the place against the block
 *       it just optimistically drew). So placement needs the burst rule, which drops <em>any</em> cell
 *       within the window. Constructed with the default {@code (100 ms, 500 ms)} windows.</li>
 *   <li><b>Break (creative instant-mine)</b> — one break emits {@code START_BREAK} plus a stream of
 *       {@code CONTINUE_BREAK} on the <em>same</em> cell. A burst-by-time rule would wrongly drop the next
 *       distinct block a player sweeps over while fast-mining, so break uses {@code burstNanos == 0}
 *       (burst rule disabled) and relies purely on the same-cell echo rule — a different cell is never
 *       dropped, but a repeat of the same cell within the window is.</li>
 * </ul>
 *
 * <p>Two rules, tuned so <b>continuous editing keeps working</b> (the previous placement debounce reset
 * its timer on every attempt, so a steady stream placed only its very first block):
 * <ol>
 *   <li><b>Burst</b> — any edit within {@code burstNanos} of the last <em>applied</em> one is the same
 *       physical action and is dropped. The window is measured from the last <em>applied</em> edit and is
 *       <em>not</em> reset by dropped attempts, so a legitimate stream of distinct edits slower than the
 *       window all go through. Set {@code burstNanos == 0} to disable this rule entirely.</li>
 *   <li><b>Same cell</b> — a repeat of the exact same target cell within the longer {@code sameCellNanos}
 *       is a delayed echo and is dropped even past the burst window. A <em>different</em> cell is never
 *       blocked by this rule, so it costs nothing for real editing.</li>
 * </ol>
 *
 * <p>Instances are single-threaded (one per {@link PeSession}, touched only on that session's inbound
 * path). The clock is passed in so the logic is deterministically unit-testable.
 */
final class PeEditDebounce {

    /** Any edit within this of the last applied one is the same physical action ({@code 0} = rule off). */
    private final long burstNanos;
    /** A repeat of the exact same cell within this is a delayed echo of the same action. */
    private final long sameCellNanos;

    private boolean primed;
    private long lastAppliedNanos;
    private long lastAppliedCell;

    /** Placement windows: 100 ms burst, 500 ms same-cell — both overridable with {@code -Dkey=ms}. */
    PeEditDebounce() {
        this(Long.getLong("jedrock.pe.placeBurstMs", 100L) * 1_000_000L,
             Long.getLong("jedrock.pe.placeSameCellMs", 500L) * 1_000_000L);
    }

    PeEditDebounce(long burstNanos, long sameCellNanos) {
        this.burstNanos = burstNanos;
        this.sameCellNanos = sameCellNanos;
    }

    /** A break debounce: no burst rule (distinct fast-mined cells all pass), same-cell echo dropped. */
    static PeEditDebounce forBreak() {
        return new PeEditDebounce(0L, Long.getLong("jedrock.pe.breakSameCellMs", 500L) * 1_000_000L);
    }

    /**
     * Decide whether an edit at the given cell and time should be applied.
     *
     * @return {@code true} to apply the edit (and record it as the new reference), {@code false} to drop
     *         it as part of the 1.1.5 double-fire. Dropped attempts do not move the reference point.
     */
    boolean accept(int x, int y, int z, long nowNanos) {
        long cell = pack(x, y, z);
        if (primed) {
            long dt = nowNanos - lastAppliedNanos;
            if (burstNanos > 0 && dt < burstNanos) {
                return false;                                   // same action — exact repeat or staircase
            }
            if (cell == lastAppliedCell && dt < sameCellNanos) {
                return false;                                   // a delayed echo of the same cell
            }
        }
        primed = true;
        lastAppliedNanos = nowNanos;
        lastAppliedCell = cell;
        return true;
    }

    /** Pack a block cell into one long for cheap equality (exact bit layout is irrelevant). */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }
}
