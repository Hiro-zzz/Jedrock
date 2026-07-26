package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_RESPAWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The 1.1.5 Respawn (0x2d) body: the spawn position as three little-endian floats (protocol 113). */
class PeRespawnEncodingTest {

    @Test
    void respawnBodyIsThreeLittleEndianFloats() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.respawn(b, 0.5f, 65.0f, 0.5f);

        assertEquals(ID_RESPAWN, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(0.5f, b.readFloatLE(), 1e-6, "x");
        assertEquals(65.0f, b.readFloatLE(), 1e-6, "y");
        assertEquals(0.5f, b.readFloatLE(), 1e-6, "z");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }
}
