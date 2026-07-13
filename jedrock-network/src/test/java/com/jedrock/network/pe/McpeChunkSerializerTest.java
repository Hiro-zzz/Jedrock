package com.jedrock.network.pe;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.BlockState;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Bedrock sub-chunk format carries a parallel 4-bit metadata array; verify a placed block's id
 * and meta land at the matching XZY position (so a Bedrock client renders the right variant).
 */
class McpeChunkSerializerTest {

    // A single block at section-local (3, 5, 7): red wool (id 35, meta 14); air everywhere else.
    private static final int BX = 3, BY = 5, BZ = 7;
    private static final int META = 14;

    @Test
    void writesBlockIdAndMetadataNibbleAtTheRightCell() {
        World world = new OneBlockWorld(Blocks.state(Blocks.WOOL, META));
        byte[] data = McpeChunkSerializer.serialize(world, 0, 0);
        ByteBuf buf = Unpooled.wrappedBuffer(data);

        assertEquals(1, buf.readUnsignedByte(), "one sub-chunk (only section 0 has a block)");
        assertEquals(0, buf.readUnsignedByte(), "sub-chunk version");

        // Block ids are written in XZY order: index = x*256 + z*16 + y.
        int i = BX * 256 + BZ * 16 + BY;
        byte[] ids = new byte[4096];
        buf.readBytes(ids);
        assertEquals(Blocks.WOOL, ids[i] & 0xFF, "wool id at the placed cell");
        assertEquals(Blocks.AIR, ids[0] & 0xFF, "air elsewhere");

        // Metadata: 2048 nibbles in the same order; even index = low nibble, odd = high nibble.
        byte[] meta = new byte[2048];
        buf.readBytes(meta);
        int nibble = (meta[i >> 1] >> ((i & 1) * 4)) & 0xF;
        assertEquals(META, nibble, "metadata nibble at the placed cell");
        assertEquals(0, meta[0] & 0xF, "no metadata where there is no block");
    }

    @Test
    void appendsAChestTileToTheChunkTail() {
        // A chest at chunk-(0,0) section-local (3,5,7) has absolute world coords (3,5,7).
        World world = new OneBlockWorld(Blocks.state(Blocks.CHEST, 0));
        byte[] data = McpeChunkSerializer.serialize(world, 0, 0);

        // The tail is a run of network-NBT tile compounds with no count prefix; the only tile here is our
        // chest, so the payload must END with exactly the bytes McpeCodec.writeChestTile produces for it.
        ByteBuf expected = Unpooled.buffer();
        McpeCodec.writeChestTile(expected, 3, 5, 7);
        byte[] tile = new byte[expected.readableBytes()];
        expected.readBytes(tile);
        expected.release();

        assertEquals(tile.length > 0, true, "chest tile is non-empty");
        byte[] tail = new byte[tile.length];
        System.arraycopy(data, data.length - tile.length, tail, 0, tile.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(tile, tail, "chunk ends with the chest's tile NBT");

        // A chest-free column has no tail: the same world with wool is shorter by exactly the tile length.
        byte[] woolData = McpeChunkSerializer.serialize(new OneBlockWorld(Blocks.state(Blocks.WOOL, 0)), 0, 0);
        assertEquals(woolData.length + tile.length, data.length, "the chest adds exactly its tile to the tail");
    }

    /** World with exactly one non-air block (a given state) at {@link #BX},{@link #BY},{@link #BZ}. */
    private record OneBlockWorld(int state) implements World {
        @Override
        public int getBlockId(int x, int y, int z) {
            return (x == BX && y == BY && z == BZ) ? state : Blocks.AIR;
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
