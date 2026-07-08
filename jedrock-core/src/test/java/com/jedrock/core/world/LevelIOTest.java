package com.jedrock.core.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the compact level-file format: metadata + {@link BlockStorage} sections + biomes round-trip. */
class LevelIOTest {

    private static LevelData meta(long seed, boolean generated) {
        return new LevelData(LevelIO.FORMAT_VERSION, seed, 48, 48, generated,
                0.5, 63.0, 0.5, 90.0f, 0.0f);
    }

    @Test
    void metadataRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        LevelData in = meta(0xABCDEF12L, true);
        LevelIO.save(file, in, new BlockStorage(), new BiomeStorage());

        LevelData out = LevelIO.load(file, new BlockStorage(), new BiomeStorage());
        assertEquals(in, out);
    }

    @Test
    void emptyWorldRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        LevelIO.save(file, meta(1L, false), new BlockStorage(), new BiomeStorage());

        BlockStorage loaded = new BlockStorage();
        BiomeStorage biomes = new BiomeStorage();
        LevelIO.load(file, loaded, biomes);
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
        LevelIO.save(file, meta(7L, false), src, new BiomeStorage());

        BlockStorage dst = new BlockStorage();
        LevelIO.load(file, dst, new BiomeStorage());

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
        LevelIO.save(file, meta(7L, true), new BlockStorage(), src);

        BiomeStorage dst = new BiomeStorage();
        LevelIO.load(file, new BlockStorage(), dst);

        assertEquals(1, dst.loadedChunks());
        // Column (x=2*16+0, z=-3*16+0) → biome 4; the flagged column → 35; an absent chunk → default.
        assertEquals(4, dst.getBiome(32, -48));
        assertEquals(35, dst.getBiome(32 + 7, -48 + 3));
        assertEquals(BiomeStorage.DEFAULT & 0xFF, dst.getBiome(9999, 9999), "absent chunk is default");
    }

    @Test
    void saveIsAtomicAndOverwrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        BlockStorage first = new BlockStorage();
        first.setId(0, 0, 0, 5);
        LevelIO.save(file, meta(1L, false), first, new BiomeStorage());
        assertTrue(Files.isRegularFile(file));

        // Overwrite with a different world; no leftover .tmp, new content wins.
        BlockStorage second = new BlockStorage();
        second.setId(0, 0, 0, 9);
        LevelIO.save(file, meta(2L, true), second, new BiomeStorage());
        assertTrue(Files.notExists(file.resolveSibling("level.jdw.tmp")), "temp file cleaned up");

        BlockStorage loaded = new BlockStorage();
        LevelData m = LevelIO.load(file, loaded, new BiomeStorage());
        assertEquals(2L, m.seed());
        assertEquals(9, loaded.getId(0, 0, 0));
    }

    @Test
    void rejectsNonLevelFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("garbage.jdw");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IOException.class, () -> LevelIO.load(file, new BlockStorage(), new BiomeStorage()));
    }
}
