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

    @Test
    void widePaletteSectionWidensBitsAndRoundTrips() {
        // A single section packed with 20 distinct block states — more than a 4-bit palette (16)
        // can hold. The serializer must widen to 5 bits/block and pack correctly (with straddling).
        int states = 20;
        World world = new ManyStateWorld(states);
        new ClientboundChunkData(world, 0, 0).write(buf);

        assertEquals(0, buf.readInt());
        assertEquals(0, buf.readInt());
        assertTrue(buf.readBoolean());
        assertEquals(1, ByteBufUtils.readVarInt(buf), "only section 0 is populated");
        ByteBufUtils.readVarInt(buf); // data size

        int bitsPerBlock = buf.readUnsignedByte();
        assertEquals(5, bitsPerBlock, "20 states need 5 bits/block");

        int paletteLen = ByteBufUtils.readVarInt(buf);
        assertEquals(states, paletteLen, "one palette entry per distinct state");
        int[] palette = new int[paletteLen];
        for (int i = 0; i < paletteLen; i++) palette[i] = ByteBufUtils.readVarInt(buf);

        int longCount = ByteBufUtils.readVarInt(buf);
        assertEquals(64 * bitsPerBlock, longCount, "compact long count");
        long[] data = new long[longCount];
        for (int i = 0; i < longCount; i++) data[i] = buf.readLong();

        // Every cell must decode back to the world's canonical state for that position.
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
            int index = (int) readPacked(data, i, bitsPerBlock);
            int expected = world.getBlockId(x, y, z);
            assertEquals(expected, palette[index], "block " + i);
        }
    }

    /** Reader side of the 1.12.2 straddling bit-packing, mirroring the serializer's writer. */
    private static long readPacked(long[] data, int i, int bitsPerBlock) {
        int bitIndex = i * bitsPerBlock;
        int longIndex = bitIndex >> 6;
        int bitOffset = bitIndex & 63;
        long value = data[longIndex] >>> bitOffset;
        int spill = bitOffset + bitsPerBlock - 64;
        if (spill > 0) {
            value |= data[longIndex + 1] << (bitsPerBlock - spill);
        }
        return value & ((1L << bitsPerBlock) - 1);
    }

    /** A single section (y 0..15) filled with {@code states} distinct non-air ids; air above. */
    private record ManyStateWorld(int states) implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            if (y < 0 || y > 15) return Blocks.AIR;
            int local = (x & 15) + (z & 15) * 16 + (y & 15) * 256;
            return Blocks.state(1 + (local % states), 0); // states for ids 1..states, all present
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

    /** Minimal world: a stone floor at {@code floorY}, air everywhere else. */
    private record FlatFloorWorld(int floorY) implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            return y == floorY ? Blocks.state(Blocks.STONE, 0) : Blocks.AIR;
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
