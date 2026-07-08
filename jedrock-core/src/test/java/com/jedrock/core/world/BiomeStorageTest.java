package com.jedrock.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeStorageTest {

    @Test
    void absentColumnsReadAsPlains() {
        BiomeStorage biomes = new BiomeStorage();
        assertEquals(BiomeStorage.DEFAULT & 0xFF, biomes.getBiome(0, 0));
        assertEquals(BiomeStorage.DEFAULT & 0xFF, biomes.getBiome(-9999, 12345));
        assertEquals(0, biomes.loadedChunks());
    }

    @Test
    void putChunkReadsBackByColumnAndFill() {
        BiomeStorage biomes = new BiomeStorage();
        byte[] chunk = new byte[256];
        chunk[(5 << 4) | 9] = 35;   // column x=9, z=5 → savanna, index (z<<4)|x
        chunk[(0 << 4) | 0] = 4;    // column x=0, z=0 → forest
        biomes.putChunk(-2, 3, chunk);

        int baseX = -2 << 4, baseZ = 3 << 4;
        assertEquals(35, biomes.getBiome(baseX + 9, baseZ + 5));
        assertEquals(4, biomes.getBiome(baseX + 0, baseZ + 0));
        assertEquals(0, biomes.getBiome(baseX + 1, baseZ + 1), "unset column reads 0 in a stored chunk");

        byte[] out = new byte[256];
        biomes.fill(-2, 3, out);
        assertEquals(35, out[(5 << 4) | 9] & 0xFF);
        assertEquals(4, out[0] & 0xFF);

        // An unstored chunk fills with the default.
        biomes.fill(100, 100, out);
        assertEquals(BiomeStorage.DEFAULT & 0xFF, out[0] & 0xFF);
        assertEquals(BiomeStorage.DEFAULT & 0xFF, out[255] & 0xFF);
    }
}
