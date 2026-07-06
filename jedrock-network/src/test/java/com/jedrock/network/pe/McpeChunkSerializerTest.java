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
