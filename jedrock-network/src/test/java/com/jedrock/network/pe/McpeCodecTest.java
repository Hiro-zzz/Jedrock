package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The protocol-113 network Item: aux packs {@code meta << 8 | count}, so state and count round-trip. */
class McpeCodecTest {

    @Test
    void itemRoundTripsStateAndCount() {
        ByteBuf b = Unpooled.buffer();
        int wool = Blocks.state(Blocks.WOOL, 14); // red wool, meta 14
        McpeCodec.writeSlot(b, wool, 42);

        McpeCodec.Item item = McpeCodec.readItem(b);
        assertEquals(wool, item.state(), "id + meta survive via the aux high byte");
        assertEquals(42, item.count(), "count is the aux low byte");
        assertFalse(b.isReadable(), "whole item consumed");
        b.release();
    }

    @Test
    void chestTileIsNetworkNbtWithIdAndCoords() {
        ByteBuf b = Unpooled.buffer();
        McpeCodec.writeChestTile(b, 1, 2, 3);

        assertEquals(0x0A, b.readUnsignedByte(), "root TAG_Compound");
        assertEquals(0, b.readUnsignedByte(), "root name length 0 (uvarint)");
        assertEquals(0x08, b.readUnsignedByte(), "TAG_String");
        assertEquals("id", readNbtString(b));
        assertEquals("Chest", readNbtString(b));
        // x = TAG_Int "x" zigzag(1) = 2
        assertEquals(0x03, b.readUnsignedByte(), "TAG_Int");
        assertEquals("x", readNbtString(b));
        assertEquals(2, b.readUnsignedByte(), "zigzag(1)");
        assertEquals(0x03, b.readUnsignedByte());
        assertEquals("y", readNbtString(b));
        assertEquals(4, b.readUnsignedByte(), "zigzag(2)");
        assertEquals(0x03, b.readUnsignedByte());
        assertEquals("z", readNbtString(b));
        assertEquals(6, b.readUnsignedByte(), "zigzag(3)");
        assertEquals(0x00, b.readUnsignedByte(), "TAG_End closes the root");
        assertFalse(b.isReadable());
        b.release();
    }

    private static String readNbtString(ByteBuf b) {
        int len = b.readUnsignedByte(); // uvarint (< 128 here)
        byte[] bytes = new byte[len];
        b.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void airItemIsJustAZeroId() {
        ByteBuf b = Unpooled.buffer();
        McpeCodec.writeSlot(b, Blocks.AIR, 0);

        McpeCodec.Item item = McpeCodec.readItem(b);
        assertEquals(Blocks.AIR, item.state());
        assertEquals(0, item.count());
        assertFalse(b.isReadable(), "air carries no further fields");
        b.release();
    }
}
