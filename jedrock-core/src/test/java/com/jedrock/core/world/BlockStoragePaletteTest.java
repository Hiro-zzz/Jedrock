package com.jedrock.core.world;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the palette-compression RAM optimization: a section is stored as a palette of distinct states plus
 * bit-packed indices (a single-state section being the degenerate size-1 palette), reads identically to a
 * full array through {@link BlockStorage#getId} / {@link BlockStorage#readSection}, promotes to a full
 * mutable array the moment a differing cell is written, and survives a full expand → recompress cycle.
 */
class BlockStoragePaletteTest {

    private static final int STONE = 1 << 4; // canonical state (id 1, meta 0)
    private static final int DIRT = 3 << 4;
    private static final int GRASS = 2 << 4;
    private static final int LOG = 17 << 4;

    private static short[] filled(int state) {
        short[] full = new short[4096];
        Arrays.fill(full, (short) state);
        return full;
    }

    /** A deterministic mix of {@code states} across the 4096 cells, so packing exercises real indices. */
    private static short[] patterned(int... states) {
        short[] full = new short[4096];
        for (int i = 0; i < full.length; i++) {
            full[i] = (short) states[i % states.length];
        }
        return full;
    }

    @Test
    void uniformSectionIsStoredCompressedButReadsFull() {
        BlockStorage s = new BlockStorage();
        s.putSection(0, 4, 0, filled(STONE));

        assertEquals(1, s.loadedSections());
        assertEquals(1, s.compressedSections(), "stored as a palette");
        assertEquals(1, s.uniformSections(), "a single-state palette");

        assertEquals(STONE, s.getId(3, 4 * 16 + 7, 11));
        short[] out = new short[4096];
        assertTrue(s.readSection(0, 4, 0, out));
        for (short v : out) assertEquals(STONE, v & 0xFFFF);
    }

    @Test
    void multiStateSectionPacksAndReadsBackEveryCell() {
        // 4 states → 2 bits per block; a fifth would need 3. Cover both widths.
        for (short[] source : new short[][]{
                patterned(BlockStorage.AIR, STONE, DIRT, GRASS),          // 4 states, 2 bits
                patterned(BlockStorage.AIR, STONE, DIRT, GRASS, LOG)}) {  // 5 states, 3 bits
            BlockStorage s = new BlockStorage();
            s.putSection(0, 0, 0, source.clone());
            assertEquals(1, s.compressedSections(), "a low-diversity section compresses");
            assertFalse(s.uniformSections() == 1, "but is not uniform");

            // Scalar read of every cell matches the source.
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
                assertEquals(source[i] & 0xFFFF, s.getId(x, y, z), "cell " + i);
            }
            // Bulk read matches the source too.
            short[] out = new short[4096];
            s.readSection(0, 0, 0, out);
            assertTrue(Arrays.equals(source, out), "readSection reproduces the packed section");
        }
    }

    @Test
    void allAirSectionStoresNothing() {
        BlockStorage s = new BlockStorage();
        s.putSection(1, 2, 3, new short[4096]); // all air
        assertEquals(0, s.loadedSections(), "an all-air section is dropped, not stored");
        assertEquals(BlockStorage.AIR, s.getId(16, 2 * 16, 48));
    }

    @Test
    void differingWritePromotesToFullAndPreservesNeighbours() {
        BlockStorage s = new BlockStorage();
        s.putSection(0, 0, 0, filled(STONE));
        assertEquals(1, s.compressedSections());

        s.setId(5, 6, 7, DIRT); // a differing write must promote the compact section to a full array
        assertEquals(0, s.compressedSections(), "the section is no longer compressed");
        assertEquals(1, s.loadedSections());

        assertEquals(DIRT, s.getId(5, 6, 7), "the edited cell changed");
        assertEquals(STONE, s.getId(6, 6, 7), "a neighbour kept the old value");
        assertEquals(STONE, s.getId(0, 0, 0), "and the corner too");
    }

    @Test
    void sameValueWriteDoesNotPromote() {
        BlockStorage s = new BlockStorage();
        s.putSection(0, 0, 0, filled(STONE));
        s.setId(5, 6, 7, STONE); // rewrite the value the cell already has
        assertEquals(1, s.compressedSections(), "a same-value write must not promote");
        assertEquals(STONE, s.getId(5, 6, 7));
    }

    @Test
    void airWriteIntoCompressedSectionPromotesAndClearsCell() {
        BlockStorage s = new BlockStorage();
        s.putSection(0, 0, 0, filled(STONE));
        s.setId(5, 6, 7, BlockStorage.AIR);
        assertEquals(0, s.compressedSections(), "clearing a cell promotes the compressed section");
        assertEquals(BlockStorage.AIR, s.getId(5, 6, 7));
        assertEquals(STONE, s.getId(6, 6, 7), "neighbours keep stone");
    }

    /**
     * The bake's closing move. Every edit expands its whole section to the mutable 8 KB array, and the
     * decoration passes edit most of the world — so without this a fresh 48×48 overworld finishes holding
     * roughly two thirds of its sections expanded, for a world that is never going to change again.
     * Compacting must give the memory back without moving a single block.
     */
    @Test
    void compactRepacksSectionsPromotedByEditing() {
        BlockStorage s = new BlockStorage();
        s.putSection(0, 0, 0, filled(STONE));
        s.putSection(1, 0, 0, filled(STONE));
        s.setId(5, 6, 7, DIRT);   // promotes section (0,0,0)
        s.setId(21, 6, 7, DIRT);  // promotes section (1,0,0)
        assertEquals(0, s.compressedSections(), "both sections were promoted by the edits");

        assertEquals(2, s.compact(), "both promoted sections were repacked");

        assertEquals(2, s.compressedSections(), "and are compact again");
        assertEquals(2, s.loadedSections(), "with nothing lost");
        assertEquals(DIRT, s.getId(5, 6, 7), "the edited cell survived the repack");
        assertEquals(DIRT, s.getId(21, 6, 7));
        assertEquals(STONE, s.getId(6, 6, 7), "and so did its neighbours");
        assertEquals(STONE, s.getId(0, 0, 0));
    }

    /** A section edited down to nothing is dropped by the repack, not kept as an empty 8 KB array. */
    @Test
    void compactDropsASectionEditedToAllAir() {
        BlockStorage s = new BlockStorage();
        short[] oneBlock = new short[4096];
        oneBlock[0] = STONE; // cell (0,0,0) — the section's first index, and its only solid block
        s.putSection(0, 0, 0, oneBlock);
        s.setId(0, 0, 0, BlockStorage.AIR); // the only solid cell — the section is now all air
        assertEquals(1, s.loadedSections(), "still allocated, as a promoted full array");

        s.compact();

        assertEquals(0, s.loadedSections(), "an all-air section is dropped, not repacked");
        assertEquals(BlockStorage.AIR, s.getId(0, 0, 0));
    }

    @Test
    void snapshotExpandsToFullSectionForPersistence() {
        // A save materializes each section to 4096 cells via SectionEntry.expandInto — verify that round-
        // trips a packed section back to the original bytes.
        BlockStorage s = new BlockStorage();
        short[] source = patterned(BlockStorage.AIR, STONE, DIRT, GRASS, LOG);
        s.putSection(2, 3, 4, source.clone());

        var entries = s.snapshotSections();
        assertEquals(1, entries.size());
        BlockStorage.SectionEntry e = entries.get(0);
        assertEquals(2, e.chunkX());
        assertEquals(3, e.sectionY());
        assertEquals(4, e.chunkZ());
        short[] expanded = new short[4096];
        assertTrue(e.expandInto(expanded));
        assertTrue(Arrays.equals(source, expanded), "expandInto reproduces the original section");
    }

    @Test
    void readSectionOfAbsentSectionIsAllAir() {
        BlockStorage s = new BlockStorage();
        short[] out = new short[4096];
        Arrays.fill(out, (short) 999);
        assertFalse(s.readSection(9, 9, 9, out), "an unallocated section is all air");
        for (short v : out) assertEquals(BlockStorage.AIR, v);
    }
}
