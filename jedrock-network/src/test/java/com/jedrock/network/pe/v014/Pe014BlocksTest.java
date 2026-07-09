package com.jedrock.network.pe.v014;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.BlockState;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.14 is hard-limited to a safe block set — in the creative menu and in the world it serializes. */
class Pe014BlocksTest {

    private static final int BLOCKS = 16 * 16 * 128; // the blockIds region at the front of the blob

    @Test
    void paletteIsConservativeSafeAndSupported() {
        int[] palette = Pe014Blocks.creativePalette();
        assertTrue(palette.length > 50, "a usable 0.14 palette (" + palette.length + ")");

        Set<Integer> states = new HashSet<>();
        for (int state : palette) {
            int id = Blocks.idOf(state);
            assertTrue(id >= 1 && id <= 255, "id in the legacy byte range: " + id);
            assertTrue(Pe014Blocks.supports(id), "every menu block is also world-supported: " + id);
            assertTrue(states.add(state), "no duplicate state: " + state);
        }
        for (int meta = 0; meta < 16; meta++) {
            assertTrue(states.contains(Blocks.state(35, meta)), "wool colour meta " + meta);
        }
    }

    @Test
    void supportsClassicBlocksButNotExoticOnes() {
        for (int classic : new int[]{1, 2, 3, 8, 9, 17, 18, 35, 45}) {
            assertTrue(Pe014Blocks.supports(classic), "classic block " + classic);
        }
        // Blocks the 0.14 client can't render (would crash it) must be rejected.
        for (int exotic : new int[]{130, 137, 138, 165, 168, 169, 236, 251}) {
            assertFalse(Pe014Blocks.supports(exotic), "exotic block " + exotic + " must be unsupported");
        }
    }

    @Test
    void unsupportedWorldBlocksSerializeAsAir() {
        byte[] blob = Mcpe014ChunkSerializer.serialize(new SolidWorld(138), 0, 0); // beacon everywhere
        for (int i = 0; i < BLOCKS; i++) {
            assertEquals(0, blob[i] & 0xFF, "unsupported block filtered to air at index " + i);
        }
    }

    @Test
    void supportedWorldBlocksReachTheClient() {
        byte[] blob = Mcpe014ChunkSerializer.serialize(new SolidWorld(1), 0, 0); // stone everywhere
        boolean anyStone = false;
        for (int i = 0; i < BLOCKS; i++) {
            if ((blob[i] & 0xFF) == 1) {
                anyStone = true;
                break;
            }
        }
        assertTrue(anyStone, "a supported block still reaches the client");
    }

    /** World filled with one block state at every cell (y 0..127). */
    private record SolidWorld(int id) implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            return (y >= 0 && y < 128) ? Blocks.state(id, 0) : Blocks.AIR;
        }

        @Override public String getName() { return "test"; }
        @Override public UUID getUniqueId() { return null; }
        @Override public Dimension getDimension() { return Dimension.OVERWORLD; }
        @Override public Collection<Player> getPlayers() { return List.of(); }
        @Override public Collection<Entity> getEntities() { return List.of(); }
        @Override public BlockState getBlockAt(int x, int y, int z) { return BlockState.AIR; }
        @Override public void setBlockAt(int x, int y, int z, BlockState state) { }
        @Override public void setBlockId(int x, int y, int z, int blockId) { }
        @Override public Location getSpawnLocation() { return null; }
    }
}
