package com.jedrock.network.handler.je.v1_8;

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
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Structural checks for the deterministic 1.8 chunk layout (verifiable without a client): the header,
 * the grouped section data (blocks as little-endian {@code id<<4|meta} shorts, then block light, then
 * sky light, then the biome map) and the exact byte sizes. A solid stone column parses unambiguously.
 */
class Java1_8ChunkDataTest {

    private static final int SECTION_BLOCKS = 8192; // 4096 * 2
    private static final int SECTION_LIGHT = 2048;

    @Test
    void serializesGrouped18ChunkLayout() {
        World world = new SolidStoneWorld();
        ByteBuf buf = Unpooled.buffer();
        new Java1_8ChunkData(world, 5, -2).write(buf);

        assertEquals(5, buf.readInt(), "chunk X");
        assertEquals(-2, buf.readInt(), "chunk Z");
        assertEquals(true, buf.readBoolean(), "ground-up continuous");
        assertEquals(0xFFFF, buf.readUnsignedShort(), "all 16 sections present");

        int present = 16;
        int expected = present * SECTION_BLOCKS + present * SECTION_LIGHT + present * SECTION_LIGHT + 256;
        int size = ByteBufUtils.readVarInt(buf);
        assertEquals(expected, size, "grouped data size");

        byte[] data = new byte[size];
        buf.readBytes(data);
        assertFalse(buf.isReadable(), "packet fully consumed");

        // Block data: section 0, cell 0 is a little-endian stone state (id<<4 = 16).
        int stone = Blocks.state(Blocks.STONE, 0);
        int cell0 = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        assertEquals(stone, cell0, "first block is stone, little-endian");
        int lastBlockIdx = (present * 4096 - 1) * 2;
        int lastCell = (data[lastBlockIdx] & 0xFF) | ((data[lastBlockIdx + 1] & 0xFF) << 8);
        assertEquals(stone, lastCell, "last block is stone");

        // Sky light region (after all blocks + all block light) is full daylight.
        int skyStart = present * SECTION_BLOCKS + present * SECTION_LIGHT;
        assertEquals((byte) 0xFF, data[skyStart], "sky light full at start");
        assertEquals((byte) 0xFF, data[skyStart + present * SECTION_LIGHT - 1], "sky light full at end");

        // Block light region is dark (zeros).
        assertEquals(0, data[present * SECTION_BLOCKS], "block light dark");

        // Biome map: 256 plains bytes at the very end.
        int biomeStart = present * SECTION_BLOCKS + present * SECTION_LIGHT + present * SECTION_LIGHT;
        assertEquals(Java1_8Protocol.PLAINS_BIOME, data[biomeStart] & 0xFF, "first biome plains");
        assertEquals(Java1_8Protocol.PLAINS_BIOME, data[data.length - 1] & 0xFF, "last biome plains");
    }

    @Test
    void unloadIsAnEmptyGroundUpColumn() {
        ByteBuf buf = Unpooled.buffer();
        Java1_8ChunkData.unload(1, 2).write(buf);
        assertEquals(1, buf.readInt());
        assertEquals(2, buf.readInt());
        assertEquals(true, buf.readBoolean());
        assertEquals(0, buf.readUnsignedShort(), "no sections");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "no data");
        assertFalse(buf.isReadable());
        buf.release();
    }

    private record SolidStoneWorld() implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            return (y >= 0 && y <= 255) ? Blocks.state(Blocks.STONE, 0) : Blocks.AIR;
        }
        @Override public String getName() { return "test"; }
        @Override public UUID getUniqueId() { return null; }
        @Override public Dimension getDimension() { return Dimension.OVERWORLD; }
        @Override public Collection<Player> getPlayers() { return List.of(); }
        @Override public Collection<Entity> getEntities() { return List.of(); }
        @Override public BlockState getBlockAt(int x, int y, int z) { return BlockState.AIR; }
        @Override public void setBlockAt(int x, int y, int z, BlockState s) { }
        @Override public void setBlockId(int x, int y, int z, int blockId) { }
        @Override public Location getSpawnLocation() { return null; }
    }
}
