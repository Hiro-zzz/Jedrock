package com.jedrock.network.je.packet;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.BlockState;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural checks for the 1.12.2 Chunk Data (0x20) encoding, serialized from a flat world.
 */
class ClientboundChunkDataTest {

    private static final int FLOOR_Y = 63;

    private final World flatWorld = new FlatFloorWorld(FLOOR_Y);
    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void tearDown() {
        buf.release();
    }

    @Test
    void encodesAConsistentFlatChunk() {
        new ClientboundChunkData(flatWorld, 2, -3).write(buf);

        assertEquals(2, buf.readInt(), "chunk X");
        assertEquals(-3, buf.readInt(), "chunk Z");
        assertTrue(buf.readBoolean(), "ground-up continuous");
        assertEquals(1 << (FLOOR_Y >> 4), ByteBufUtils.readVarInt(buf), "primary bit mask (one section)");

        int size = ByteBufUtils.readVarInt(buf);
        int dataStart = buf.readerIndex();

        // --- the single floor section ---
        assertEquals(4, buf.readUnsignedByte(), "bits per block");
        assertEquals(2, ByteBufUtils.readVarInt(buf), "palette length");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "palette[0] = air");
        assertEquals(1 << 4, ByteBufUtils.readVarInt(buf), "palette[1] = stone");

        assertEquals(256, ByteBufUtils.readVarInt(buf), "data array length (longs)");
        int localFloorY = FLOOR_Y & 15;
        for (int i = 0; i < 256; i++) {
            long expected = (i >> 4) == localFloorY ? 0x1111111111111111L : 0L;
            assertEquals(expected, buf.readLong(), "long " + i);
        }
        buf.skipBytes(2048); // block light
        buf.skipBytes(2048); // sky light

        for (int i = 0; i < 256; i++) {
            assertEquals(1, buf.readUnsignedByte(), "biome " + i);
        }

        assertEquals(size, buf.readerIndex() - dataStart, "declared size matches actual data bytes");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "block entity count");
        assertFalse(buf.isReadable(), "no trailing bytes");
    }

    /** Minimal world: a stone floor at {@code floorY}, air everywhere else. */
    private record FlatFloorWorld(int floorY) implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            return y == floorY ? Blocks.STONE : Blocks.AIR;
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
