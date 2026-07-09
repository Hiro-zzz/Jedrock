package com.jedrock.network.pe.v014;

import com.jedrock.api.world.Blocks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The 0.14 ContainerSetContent (0xb9) and item-slot wire format (big-endian, protocol 45). */
class Mcpe014ContainerContentTest {

    @Test
    void encodesCreativeContainer() {
        int[] states = {Blocks.state(35, 14), Blocks.state(1, 0)}; // red wool, stone
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.containerSetContent(b, Mcpe014Packets.WINDOW_ID_CREATIVE, states, 1);

        assertEquals(0xb9, b.readUnsignedByte(), "ContainerSetContent id");
        assertEquals(0x79, b.readUnsignedByte(), "creative window id");
        assertEquals(2, b.readShort(), "slot count");

        // slot 0 — red wool
        assertEquals(35, b.readShort(), "id");
        assertEquals(1, b.readByte(), "count");
        assertEquals(14, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "nbt length");
        // slot 1 — stone
        assertEquals(1, b.readShort());
        assertEquals(1, b.readByte());
        assertEquals(0, b.readShort());
        assertEquals(0, b.readShort());

        assertEquals(0, b.readShort(), "trailing hotbar-link count");
        assertFalse(b.isReadable(), "packet fully consumed");
        b.release();
    }

    @Test
    void airSlotIsASingleZeroShort() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.writeSlot(b, Blocks.AIR, 1);
        assertEquals(0, b.readShort(), "air slot is just short 0");
        assertFalse(b.isReadable());
        b.release();
    }
}
