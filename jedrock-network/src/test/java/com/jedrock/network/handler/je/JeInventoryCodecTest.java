package com.jedrock.network.handler.je;

import com.jedrock.api.world.Blocks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The core's 41-slot inventory (0-8 hotbar, 9-35 main, 36-39 armor, 40 off-hand) maps onto Java's
 * window-0 layout (armor 5-8, main 9-35, hotbar 36-44, off-hand 45). Pin that mapping and the Slot
 * encoding, since a wrong index shows an item in the wrong place.
 */
class JeInventoryCodecTest {

    @Test
    void mapsHotbarMainArmorAndEncodesSlots() {
        int[] states = new int[41];
        int[] counts = new int[41];
        int stone = Blocks.state(Blocks.STONE, 0);      // id 1, meta 0
        int wool = Blocks.state(Blocks.WOOL, 14);       // id 35, meta 14
        int dirt = Blocks.state(Blocks.DIRT, 0);        // id 3 — stand-in for a helmet
        states[0] = stone; counts[0] = 5;                // hotbar slot 0 → window 36
        states[9] = wool;  counts[9] = 1;                // main slot 9 → window 9
        states[36] = dirt; counts[36] = 1;               // armor slot (helmet) → window 5

        ByteBuf buf = Unpooled.buffer();
        JeInventoryCodec.writeWindowItems(buf, states, counts, JeInventoryCodec.WINDOW_SLOTS_1_12);

        assertEquals(0, buf.readUnsignedByte(), "window id 0");
        assertEquals(46, buf.readShort(), "1.12.2 window has 46 slots");

        int[] slotStart = new int[46];
        // Walk the variable-length slots, recording where each begins.
        for (int w = 0; w < 46; w++) {
            slotStart[w] = buf.readerIndex();
            short id = buf.readShort();
            if (id != -1) {
                buf.readByte();      // count
                buf.readShort();     // damage
                buf.readByte();      // no-NBT marker
            }
        }

        // Window 5 (first armor slot) = the helmet stand-in dirt(3) count 1.
        buf.readerIndex(slotStart[5]);
        assertEquals(Blocks.DIRT, buf.readShort());
        assertEquals(1, buf.readUnsignedByte());

        // Window 9 = wool(35) meta 14 count 1.
        buf.readerIndex(slotStart[9]);
        assertEquals(Blocks.WOOL, buf.readShort());
        assertEquals(1, buf.readUnsignedByte());
        assertEquals(14, buf.readShort());
        assertEquals(0, buf.readUnsignedByte());

        // Window 36 = hotbar slot 0 = stone(1) count 5.
        buf.readerIndex(slotStart[36]);
        assertEquals(Blocks.STONE, buf.readShort());
        assertEquals(5, buf.readUnsignedByte());
        assertEquals(0, buf.readShort());

        // Window 0 (crafting output) is empty (-1).
        buf.readerIndex(slotStart[0]);
        assertEquals(-1, buf.readShort());

        buf.release();
    }

    @Test
    void oneEightWindowHas45Slots() {
        ByteBuf buf = Unpooled.buffer();
        JeInventoryCodec.writeWindowItems(buf, new int[41], new int[41], JeInventoryCodec.WINDOW_SLOTS_1_8);
        assertEquals(0, buf.readUnsignedByte());
        assertEquals(45, buf.readShort(), "1.8 window has 45 slots (no off-hand)");
        buf.release();
    }
}
