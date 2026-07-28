package com.jedrock.network.pe;

import com.jedrock.api.world.Dimension;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_CHANGE_DIMENSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.1.5 ChangeDimension (0x3d) body, and the number that goes in it.
 *
 * <p>The dimension is the part worth pinning: Bedrock numbers its dimensions 0/1/2 while Java uses
 * 0/-1/1, and the world model speaks Java's. Sending the nether as {@code -1} here would zigzag to a
 * perfectly valid varint that means something else entirely — the quiet kind of wrong.
 */
class PeChangeDimensionEncodingTest {

    @Test
    void changeDimensionBodyIsASignedVarIntAThreeFloatVectorAndABool() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.changeDimension(b, 1, 0.5f, 40.0f, -8.5f, true);

        assertEquals(ID_CHANGE_DIMENSION, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "dimension");
        assertEquals(0.5f, b.readFloatLE(), 1e-6, "x");
        assertEquals(40.0f, b.readFloatLE(), 1e-6, "y");
        assertEquals(-8.5f, b.readFloatLE(), 1e-6, "z");
        assertTrue(b.readBoolean(), "respawn");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void bedrockNumbersItsDimensionsDifferentlyFromJava() {
        assertEquals(0, PeSession.bedrockDimension(Dimension.OVERWORLD));
        assertEquals(1, PeSession.bedrockDimension(Dimension.NETHER), "Bedrock's nether is 1, not Java's -1");
        assertEquals(2, PeSession.bedrockDimension(Dimension.END));
    }
}
