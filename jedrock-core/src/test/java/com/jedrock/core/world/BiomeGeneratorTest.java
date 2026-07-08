package com.jedrock.core.world;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The biome map is deterministic, valid, and varied over the world. */
class BiomeGeneratorTest {

    @Test
    void sameSeedReproducesSameBiomes() {
        BiomeGenerator a = new BiomeGenerator(2024L);
        BiomeGenerator b = new BiomeGenerator(2024L);
        for (int x = -400; x <= 400; x += 37) {
            for (int z = -400; z <= 400; z += 37) {
                assertEquals(a.biomeAt(x, z), b.biomeAt(x, z), "column " + x + "," + z);
            }
        }
    }

    @Test
    void alwaysReturnsAKnownBiome() {
        BiomeGenerator gen = new BiomeGenerator(1L);
        for (int x = -200; x <= 200; x += 25) {
            for (int z = -200; z <= 200; z += 25) {
                assertNotNull(gen.biomeAt(x, z));
            }
        }
    }

    @Test
    void producesMoreThanOneBiomeAcrossTheWorld() {
        BiomeGenerator gen = new BiomeGenerator(0x5EED1EAFL);
        Set<Biome> seen = EnumSet.noneOf(Biome.class);
        for (int x = -768; x <= 768; x += 16) {
            for (int z = -768; z <= 768; z += 16) {
                seen.add(gen.biomeAt(x, z));
            }
        }
        assertTrue(seen.size() >= 2, "a 48×48 world should span at least two biomes, saw " + seen);
    }
}
