package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.inventory.Container;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the compact level-file format: metadata + {@link BlockStorage} sections + biomes + chests round-trip. */
class LevelIOTest {

    private static LevelData meta(long seed, boolean generated) {
        return meta(seed, generated, Dimension.OVERWORLD);
    }

    private static LevelData meta(long seed, boolean generated, Dimension dimension) {
        return new LevelData(LevelIO.FORMAT_VERSION, seed, 48, 48, generated,
                0.5, 63.0, 0.5, 90.0f, 0.0f, dimension.getId());
    }

    @Test
    void theDimensionRidesInTheHeader(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(7L, true, Dimension.NETHER), new BlockStorage(), new BiomeStorage(), chests());

        LevelData out = LevelIO.load(file, new BlockStorage(), new BiomeStorage(), chests());
        assertEquals(Dimension.NETHER.getId(), out.dimensionId());
    }

    /** A fresh, empty chest map — most tests don't exercise containers. */
    private static Map<Long, Container> chests() {
        return new HashMap<>();
    }

    @Test
    void metadataRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        LevelData in = meta(0xABCDEF12L, true);
        LevelIO.save(file, in, new BlockStorage(), new BiomeStorage(), chests());

        LevelData out = LevelIO.load(file, new BlockStorage(), new BiomeStorage(), chests());
        assertEquals(in, out);
    }

    @Test
    void emptyWorldRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(1L, false), new BlockStorage(), new BiomeStorage(), chests());

        BlockStorage loaded = new BlockStorage();
        BiomeStorage biomes = new BiomeStorage();
        LevelIO.load(file, loaded, biomes, chests());
        assertEquals(0, loaded.loadedSections());
        assertEquals(0, biomes.loadedChunks());
    }

    @Test
    void blocksRoundTripAcrossSectionsAndSigns(@TempDir Path dir) throws IOException {
        BlockStorage src = new BlockStorage();
        // Spread edits across several columns/sections, including negative coordinates.
        src.setId(0, 0, 0, 1);
        src.setId(15, 255, 15, 0xFFF);          // top section, max 12-bit state
        src.setId(-1, 70, -1, 0x1000);          // CoreWorld's REMOVED sentinel (bit 12) must survive
        src.setId(-40, 5, 33, 42);
        src.setId(500, 128, -500, 0x0ABC);
        int sectionsBefore = src.loadedSections();

        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(7L, false), src, new BiomeStorage(), chests());

        BlockStorage dst = new BlockStorage();
        LevelIO.load(file, dst, new BiomeStorage(), chests());

        assertEquals(sectionsBefore, dst.loadedSections(), "section count preserved");
        assertEquals(1, dst.getId(0, 0, 0));
        assertEquals(0xFFF, dst.getId(15, 255, 15));
        assertEquals(0x1000, dst.getId(-1, 70, -1));
        assertEquals(42, dst.getId(-40, 5, 33));
        assertEquals(0x0ABC, dst.getId(500, 128, -500));
        assertEquals(0, dst.getId(1, 1, 1), "untouched cell is still air");
    }

    @Test
    void biomeMapRoundTrips(@TempDir Path dir) throws IOException {
        BiomeStorage src = new BiomeStorage();
        byte[] chunkA = new byte[256];
        java.util.Arrays.fill(chunkA, (byte) 4); // forest
        chunkA[(3 << 4) | 7] = 35;               // one savanna column, index (z<<4)|x
        src.putChunk(2, -3, chunkA);

        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(7L, true), new BlockStorage(), src, chests());

        BiomeStorage dst = new BiomeStorage();
        LevelIO.load(file, new BlockStorage(), dst, chests());

        assertEquals(1, dst.loadedChunks());
        // Column (x=2*16+0, z=-3*16+0) → biome 4; the flagged column → 35; an absent chunk → default.
        assertEquals(4, dst.getBiome(32, -48));
        assertEquals(35, dst.getBiome(32 + 7, -48 + 3));
        assertEquals(BiomeStorage.DEFAULT & 0xFF, dst.getBiome(9999, 9999), "absent chunk is default");
    }

    @Test
    void chestContentsRoundTrip(@TempDir Path dir) throws IOException {
        Map<Long, Container> src = chests();
        Container chest = new Container(27);
        chest.set(0, Blocks.state(Blocks.STONE, 0), 64);
        chest.set(5, Blocks.state(Blocks.WOOL, 14), 3);
        long pos = 0x1234ABCDL;
        src.put(pos, chest);
        src.put(999L, new Container(27)); // an empty container must NOT be persisted

        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(1L, true), new BlockStorage(), new BiomeStorage(), src);

        Map<Long, Container> dst = chests();
        LevelIO.load(file, new BlockStorage(), new BiomeStorage(), dst);

        assertEquals(1, dst.size(), "only the non-empty chest is stored");
        Container out = dst.get(pos);
        assertEquals(Blocks.state(Blocks.STONE, 0), out.stateAt(0));
        assertEquals(64, out.countAt(0));
        assertEquals(Blocks.state(Blocks.WOOL, 14), out.stateAt(5));
        assertEquals(3, out.countAt(5));
        assertTrue(out.isEmpty(1), "untouched slot stays empty");
    }

    @Test
    void aCustomItemInAChestKeepsItsKeyAcrossARestart(@TempDir Path dir) throws Exception {
        Map<Long, Container> src = chests();
        Container chest = new Container(27);
        chest.set(0, Blocks.state(276, 0), 1, "frostblade");
        chest.set(1, Blocks.state(276, 0), 1); // the same sword, ordinary
        src.put(42L, chest);

        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(1L, true), new BlockStorage(), new BiomeStorage(), src);
        Map<Long, Container> dst = chests();
        LevelIO.load(file, new BlockStorage(), new BiomeStorage(), dst);

        Container out = dst.get(42L);
        assertEquals("frostblade", out.customKeyAt(0),
                "the KEY is what persists — the file is read long before any plugin defines it");
        assertNull(out.customKeyAt(1), "and an ordinary stack stays ordinary");
    }

    @Test
    void saveIsAtomicAndOverwrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        BlockStorage first = new BlockStorage();
        first.setId(0, 0, 0, 5);
        LevelIO.save(file, meta(1L, false), first, new BiomeStorage(), chests());
        assertTrue(Files.isRegularFile(file));

        // Overwrite with a different world; no leftover .tmp, new content wins.
        BlockStorage second = new BlockStorage();
        second.setId(0, 0, 0, 9);
        LevelIO.save(file, meta(2L, true), second, new BiomeStorage(), chests());
        assertTrue(Files.notExists(file.resolveSibling("level.jdw.tmp")), "temp file cleaned up");

        BlockStorage loaded = new BlockStorage();
        LevelData m = LevelIO.load(file, loaded, new BiomeStorage(), chests());
        assertEquals(2L, m.seed());
        assertEquals(9, loaded.getId(0, 0, 0));
    }

    @Test
    void rejectsNonLevelFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("garbage.jdw");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IOException.class,
                () -> LevelIO.load(file, new BlockStorage(), new BiomeStorage(), chests()));
    }
}
