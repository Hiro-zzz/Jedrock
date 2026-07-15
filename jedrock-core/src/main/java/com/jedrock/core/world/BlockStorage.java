package com.jedrock.core.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart of Jedrock's "world as illusion" storage.
 *
 * <p>The world is a flat matrix of primitive block IDs addressed with pure bit operations. Nothing is a
 * real simulation: a block is just a {@code short} id. Memory is spent only where blocks actually exist —
 * blocks are grouped into 16×16×16 sections, and a section is allocated only on the first non-air write; a
 * missing column or section reads as {@link #AIR}, the ultimate lazy default. Height is fixed to 0..255
 * (16 sections), matching JE 1.12.2.
 *
 * <p><b>Compression.</b> A baked world is dominated by sections with only a handful of distinct states
 * (air, stone, dirt, grass, the odd log/ore). Rather than a flat {@code short[4096]} (8 KB) per section,
 * an unedited section is stored as a {@link PalettedSection}: a small palette of distinct states plus the
 * 4096 cell indices bit-packed at the smallest width that fits the palette (2 bits ≈ 1 KB, 4 bits ≈ 2 KB).
 * A section is therefore one of three shapes:
 * <ul>
 *   <li>{@code null} — all air,</li>
 *   <li>a {@link PalettedSection} — the compact, immutable form baking / loading installs,</li>
 *   <li>a {@code short[4096]} — a mutable full array, which a section is promoted to on its first edit
 *       (player edits are rare and localized, so few sections ever pay the full 8 KB).</li>
 * </ul>
 * Reads go through {@link #cellOf} (scalar) or {@link #readSection} (bulk), which hide the distinction, so
 * the chunk hot path is unchanged bar one dispatch per section.
 *
 * <p>Threading: reads are safe from any thread. Writes arrive from the network threads (a player edit on
 * either edition); the rare allocation / promotion of a section is guarded by the column lock so two
 * first-writers can't clobber each other. Two writes to the <b>same</b> cell race to a last-writer-wins
 * result, and a read concurrent with a write is a benign torn read — both tolerated by design.
 */
public final class BlockStorage {

    /** Block id representing air / "nothing here". */
    public static final short AIR = 0;

    private static final int MIN_Y = 0;
    private static final int MAX_Y = 255;
    private static final int SECTIONS_PER_COLUMN = 16;
    private static final int SECTION_CELLS = 4096;

    /** columnKey(chunkX, chunkZ) → 16 section slots: {@code null} | {@link PalettedSection} | {@code short[4096]}. */
    private final ConcurrentHashMap<Long, Object[]> columns = new ConcurrentHashMap<>();

    /** @return block id at the given coordinates, or {@link #AIR} if nothing is stored there. */
    public int getId(int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return AIR;
        }
        Object[] column = columns.get(columnKey(x >> 4, z >> 4));
        if (column == null) {
            return AIR;
        }
        return cellOf(column[y >> 4], index(x, y, z));
    }

    /** Store a block id. Writing {@link #AIR} never allocates new storage. */
    public void setId(int x, int y, int z, int id) {
        if (y < MIN_Y || y > MAX_Y) {
            return;
        }
        int idx = index(x, y, z);

        if (id == AIR) {
            // Only clear if the backing storage already exists; do not allocate for air.
            Object[] column = columns.get(columnKey(x >> 4, z >> 4));
            if (column == null) return;
            int sy = y >> 4;
            if (cellOf(column[sy], idx) == AIR) return; // already air — nothing to clear (or promote)
            mutableSection(column, sy)[idx] = AIR;
            return;
        }

        Object[] column = columns.computeIfAbsent(columnKey(x >> 4, z >> 4),
                k -> new Object[SECTIONS_PER_COLUMN]);
        int sy = y >> 4;
        if (cellOf(column[sy], idx) == (id & 0xFFFF)) {
            return; // the cell already holds this value — no change, no need to promote a compact section
        }
        mutableSection(column, sy)[idx] = (short) id;
    }

    /**
     * Return {@code column[sy]} as a mutable {@code short[4096]}, allocating it on first touch or expanding
     * a compact {@link PalettedSection} (filled from its palette before publishing). The column lock guards
     * the rare allocation / promotion; the common case (already a full array) returns without locking.
     */
    private static short[] mutableSection(Object[] column, int sy) {
        Object sec = column[sy];
        if (sec instanceof short[] full) {
            return full;
        }
        synchronized (column) {
            sec = column[sy];
            if (sec instanceof short[] full) {
                return full;
            }
            short[] full = new short[SECTION_CELLS];
            if (sec instanceof PalettedSection p) {
                p.fill(full);           // expand compact → full, before publishing so readers stay consistent
            }
            column[sy] = full;
            return full;
        }
    }

    /**
     * Read one cell out of a raw section object, transparently across the three shapes: {@code null} (all
     * air), a {@link PalettedSection} (unpacks the index), and a mutable {@code short[4096]}. {@code idx}
     * is a {@link #index} value.
     */
    public static int cellOf(Object sec, int idx) {
        if (sec instanceof PalettedSection p) {
            return p.get(idx);
        }
        if (sec instanceof short[] full) {
            return full[idx] & 0xFFFF;
        }
        return AIR;
    }

    /**
     * The raw section object at a position (may be {@code null} / {@link PalettedSection} / {@code
     * short[4096]}), for a caller that reads individual cells with {@link #cellOf}. One map lookup for the
     * whole section. Reading concurrently with a write is a benign race, same as {@link #getId}.
     */
    public Object section(int chunkX, int sectionY, int chunkZ) {
        Object[] column = columns.get(columnKey(chunkX, chunkZ));
        return column == null ? null : column[sectionY];
    }

    /**
     * Bulk-read a whole 16³ section into {@code out} (length 4096) after a single map lookup: {@code null}
     * → all air, a {@link PalettedSection} → unpack, a {@code short[4096]} → copy.
     * @return whether the section holds any non-air cell (so an all-air section can be skipped).
     */
    public boolean readSection(int chunkX, int sectionY, int chunkZ, short[] out) {
        Object[] column = columns.get(columnKey(chunkX, chunkZ));
        return fillFrom(column == null ? null : column[sectionY], out);
    }

    /** Fill {@code out} (length 4096) from any section shape; return whether any cell is non-air. */
    private static boolean fillFrom(Object sec, short[] out) {
        if (sec instanceof PalettedSection p) {
            return p.fill(out);
        }
        if (sec instanceof short[] full) {
            System.arraycopy(full, 0, out, 0, out.length);
            for (short v : full) {
                if (v != AIR) {
                    return true;
                }
            }
            return false;
        }
        Arrays.fill(out, AIR); // null → all air
        return false;
    }

    /**
     * One stored section: its position and the raw section object. {@link #expandInto} materializes it to
     * a full {@code short[4096]} — a persistence pass reuses one scratch buffer across every section, so a
     * compact section never has to be held expanded.
     */
    public record SectionEntry(int chunkX, int sectionY, int chunkZ, Object section) {
        /** Expand this section into {@code out} (length 4096); returns whether it has any non-air cell. */
        public boolean expandInto(short[] out) {
            return fillFrom(section, out);
        }
    }

    /**
     * Snapshot every currently allocated section for saving. The list is a fresh structure but the section
     * objects are shared (not copied), so hold it only for the duration of a save.
     */
    public List<SectionEntry> snapshotSections() {
        List<SectionEntry> out = new ArrayList<>();
        for (var e : columns.entrySet()) {
            long key = e.getKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            Object[] column = e.getValue();
            for (int sy = 0; sy < column.length; sy++) {
                if (column[sy] != null) {
                    out.add(new SectionEntry(chunkX, sy, chunkZ, column[sy]));
                }
            }
        }
        return out;
    }

    /**
     * Install a whole section at the given position, allocating the column if needed. For single-threaded
     * bulk load (world load / bake); {@code data} must have length 4096. It is compressed to a compact
     * {@link PalettedSection} (an all-air section is dropped, leaving the slot air; a pathologically diverse
     * one keeps the full array). The input array is not retained unless kept as the full fallback.
     */
    public void putSection(int chunkX, int sectionY, int chunkZ, short[] data) {
        Object compact = PalettedSection.compress(data);
        if (compact == null) {
            return; // all air — store nothing, the slot reads as air
        }
        Object[] column = columns.computeIfAbsent(columnKey(chunkX, chunkZ),
                k -> new Object[SECTIONS_PER_COLUMN]);
        column[sectionY] = compact;
    }

    /** Number of currently allocated sections (compact + full) — useful for tests and introspection. */
    public int loadedSections() {
        return countSections(false);
    }

    /** Of the {@link #loadedSections}, how many are held compressed as a {@link PalettedSection}. */
    public int compressedSections() {
        return countSections(true);
    }

    /** Of the {@link #loadedSections}, how many are a single repeated block (a size-1 palette). */
    public int uniformSections() {
        int count = 0;
        for (Object[] column : columns.values()) {
            for (Object section : column) {
                if (section instanceof PalettedSection p && p.uniform()) count++;
            }
        }
        return count;
    }

    private int countSections(boolean compressedOnly) {
        int count = 0;
        for (Object[] column : columns.values()) {
            for (Object section : column) {
                if (section != null && (!compressedOnly || section instanceof PalettedSection)) count++;
            }
        }
        return count;
    }

    // Index inside a 16×16×16 section: y is the outer axis, then z, then x.
    // Package-visible so CoreWorld addresses a raw section with the same scheme.
    static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    // Pack two full 32-bit chunk coordinates into one long (collision-free over int range).
    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * A section compressed as a palette of distinct block states plus the 4096 cell indices bit-packed at
     * the smallest width that fits the palette. Immutable — a write promotes the whole section back to a
     * mutable {@code short[4096]} (see {@link BlockStorage#mutableSection}). Indices are packed without
     * straddling a {@code long} boundary (a few bits per long may go unused) — simpler and cheaper to
     * unpack than the straddling wire layout, and RAM is the goal, not the last bit.
     */
    static final class PalettedSection {

        private static final long[] NO_DATA = new long[0];

        private final short[] palette;   // distinct states; index into this is what {@link #data} packs
        private final int bits;          // bits per index; 0 for a uniform (single-state) section
        private final long[] data;       // 4096 indices packed at {@code bits} each; empty when uniform
        private final boolean hasNonAir;

        private PalettedSection(short[] palette, int bits, long[] data, boolean hasNonAir) {
            this.palette = palette;
            this.bits = bits;
            this.data = data;
            this.hasNonAir = hasNonAir;
        }

        boolean uniform() {
            return palette.length == 1;
        }

        /** The block id at cell {@code idx} (0..4095). */
        int get(int idx) {
            if (bits == 0) {
                return palette[0] & 0xFFFF; // uniform — every cell is the one palette entry
            }
            int per = 64 / bits;
            int off = (idx % per) * bits;
            int p = (int) ((data[idx / per] >>> off) & ((1L << bits) - 1));
            return palette[p] & 0xFFFF;
        }

        /** Expand into {@code out} (length 4096); returns {@link #hasNonAir}. */
        boolean fill(short[] out) {
            if (bits == 0) {
                Arrays.fill(out, palette[0]);
                return hasNonAir;
            }
            int per = 64 / bits;
            long mask = (1L << bits) - 1;
            for (int i = 0; i < SECTION_CELLS; i++) {
                int off = (i % per) * bits;
                out[i] = palette[(int) ((data[i / per] >>> off) & mask)];
            }
            return hasNonAir;
        }

        /**
         * Compress a full {@code short[4096]} into a {@link PalettedSection}, or {@code null} if the whole
         * section is air. A section with too many distinct states to benefit (over 256 — unreachable with
         * Jedrock's block set) is returned as the original full array, adopted as a mutable section.
         */
        static Object compress(short[] full) {
            short[] palette = new short[16];
            int n = 0;
            cell:
            for (short v : full) {
                for (int i = 0; i < n; i++) {
                    if (palette[i] == v) continue cell;
                }
                if (n == palette.length) {
                    palette = Arrays.copyOf(palette, palette.length * 2);
                }
                palette[n++] = v;
                if (n > 256) {
                    return full; // too diverse to pack usefully — keep the full array as a mutable section
                }
            }
            palette = Arrays.copyOf(palette, n);
            boolean hasNonAir = anyNonAir(palette);

            if (n == 1) {
                return palette[0] == AIR ? null : new PalettedSection(palette, 0, NO_DATA, true);
            }

            int bits = 32 - Integer.numberOfLeadingZeros(n - 1); // ceil(log2(n)); n>=2 → bits>=1
            int per = 64 / bits;
            long[] data = new long[(SECTION_CELLS + per - 1) / per];
            for (int i = 0; i < SECTION_CELLS; i++) {
                int p = indexOf(palette, full[i]);
                data[i / per] |= (long) p << ((i % per) * bits);
            }
            return new PalettedSection(palette, bits, data, hasNonAir);
        }

        private static int indexOf(short[] palette, short v) {
            for (int i = 0; i < palette.length; i++) {
                if (palette[i] == v) return i;
            }
            return 0; // unreachable — v was collected into the palette
        }

        private static boolean anyNonAir(short[] palette) {
            for (short v : palette) {
                if (v != AIR) return true;
            }
            return false;
        }
    }
}
