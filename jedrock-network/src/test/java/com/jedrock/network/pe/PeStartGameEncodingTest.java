package com.jedrock.network.pe;

import com.jedrock.api.world.Dimension;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_START_GAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The head of the 1.1.5 StartGame body, up to and including the dimension.
 *
 * <p>The dimension is why this test exists: a player who logged out in the nether is joined straight back
 * into it, and this is the only field that tells the client what kind of world it is arriving in. The
 * client reads the whole body positionally, so the number matters twice over — as the wrong sky if it is
 * wrong, and as a body shifted by one field if it were ever written in the wrong place.
 */
class PeStartGameEncodingTest {

    @Test
    void theDimensionRidesBetweenTheSeedAndTheGenerator() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.startGame(b, 7L, 1, PeSession.bedrockDimension(Dimension.NETHER),
                1.5, 40.0, -8.5, 1, 40, -9);

        assertEquals(ID_START_GAME, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(7L, ByteBufUtils.readVarLong(b) >> 1, "self entity id (zigzag)");
        assertEquals(7L, ByteBufUtils.readVarLong(b), "runtime entity id");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "game mode");
        assertEquals(1.5f, b.readFloatLE(), 1e-6, "x");
        assertEquals(40.0f, b.readFloatLE(), 1e-6, "y");
        assertEquals(-8.5f, b.readFloatLE(), 1e-6, "z");
        b.readFloatLE(); // yaw
        b.readFloatLE(); // pitch
        assertEquals(12345, ByteBufUtils.readSignedVarInt(b), "seed");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "dimension — Bedrock's nether, not Java's -1");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "generator, still right behind it");
        b.release();
    }

    @Test
    void anOverworldJoinIsStillAnnouncedAsZero() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.startGame(b, 1L, 1, PeSession.bedrockDimension(Dimension.OVERWORLD),
                0, 64, 0, 0, 64, 0);

        ByteBufUtils.readVarInt(b);          // id
        ByteBufUtils.readVarLong(b);         // self entity id
        ByteBufUtils.readVarLong(b);         // runtime entity id
        ByteBufUtils.readSignedVarInt(b);    // game mode
        b.skipBytes(5 * Float.BYTES);        // position + rotation
        ByteBufUtils.readSignedVarInt(b);    // seed
        assertEquals(0, ByteBufUtils.readSignedVarInt(b), "the default world's join is unchanged");
        b.release();
    }
}
