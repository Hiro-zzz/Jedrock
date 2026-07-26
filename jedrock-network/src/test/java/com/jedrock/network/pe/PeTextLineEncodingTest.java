package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level check for a hologram line on PE 1.1.5 (protocol 113), pinned against PocketMine-MP at tag
 * {@code 1.7dev-27} ({@code AddEntityPacket} + {@code FloatingTextParticle}).
 *
 * <p>Worth pinning because 1.1.5 and 0.14 disagree on almost every detail of the same hack: here the flags
 * are a zigzag LONG carrying nametag-visibility bits, and the nametag sits at index 4 — where 0.14 uses a
 * flags BYTE, a separate show-nametag index, and index 2 (see {@code Mcpe014AddEntityTest}).
 */
class PeTextLineEncodingTest {

    @Test
    void textLineIsAnImmobileItemEntityWithANametag() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.addTextLine(b, 1001L, 8.0, 70.0, 8.0, "§6Hello");

        assertEquals(0x0D, ByteBufUtils.readVarInt(b), "AddEntity id");
        assertEquals(1001L, readSignedVarLong(b), "entity unique id");
        assertEquals(1001L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(64, ByteBufUtils.readVarInt(b), "type: an item entity — 1.1.5 has no armor stand");
        assertEquals(8.0f, b.readFloatLE(), "x");
        assertEquals(69.25f, b.readFloatLE(), "y: PocketMine's own -0.75 so the text lands where asked");
        assertEquals(8.0f, b.readFloatLE(), "z");
        assertEquals(0f, b.readFloatLE(), "motion x");
        assertEquals(0f, b.readFloatLE(), "motion y");
        assertEquals(0f, b.readFloatLE(), "motion z");
        assertEquals(0f, b.readFloatLE(), "pitch — before yaw at 113");
        assertEquals(0f, b.readFloatLE(), "yaw");
        assertEquals(0, ByteBufUtils.readVarInt(b), "attributes: none");

        assertEquals(2, ByteBufUtils.readVarInt(b), "metadata entry count");
        assertEquals(0, ByteBufUtils.readVarInt(b), "DATA_FLAGS key");
        assertEquals(7, ByteBufUtils.readVarInt(b), "DATA_FLAGS type: LONG at 113 (a BYTE at 0.14)");
        long flags = readSignedVarLong(b);
        assertEquals((1L << 14) | (1L << 15) | (1L << 16), flags,
                "can-show + always-show nametag, and immobile");

        assertEquals(4, ByteBufUtils.readVarInt(b), "DATA_NAMETAG key: index 4 at 113 (index 2 at 0.14)");
        assertEquals(4, ByteBufUtils.readVarInt(b), "DATA_NAMETAG type: STRING");
        assertEquals("§6Hello", ByteBufUtils.readString(b), "the floating text");

        assertEquals(0, ByteBufUtils.readVarInt(b), "entity links: none");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    private static long readSignedVarLong(ByteBuf b) {
        long raw = ByteBufUtils.readVarLong(b);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
