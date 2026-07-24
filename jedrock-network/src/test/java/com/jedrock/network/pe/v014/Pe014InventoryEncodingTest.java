package com.jedrock.network.pe.v014;

import com.jedrock.api.world.Blocks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the 0.14 player-inventory sync, PMMP-shaped ({@code PlayerInventory::
 * sendContents} / {@code ContainerSetSlotPacket} in the 0.14 tree): window 0 ContainerSetContent with
 * the 36 storage slots and the 9-entry {@code i + 9} hotbar-link table, and the single-slot
 * ContainerSetSlot form.
 */
class Pe014InventoryEncodingTest {

    @Test
    void playerInventoryCarries36SlotsAndTheHotbarLinks() {
        int[] states = new int[36];
        int[] counts = new int[36];
        states[0] = Blocks.state(267, 0); counts[0] = 1;  // an iron sword on the first hotbar slot
        states[9] = Blocks.state(1, 0);   counts[9] = 64; // a stone stack in main storage

        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.playerInventory(b, states, counts);

        assertEquals(Mcpe014Packets.ID_CONTAINER_SET_CONTENT, b.readUnsignedByte(), "packet id");
        assertEquals(Mcpe014Packets.WINDOW_ID_PLAYER, b.readUnsignedByte(), "window 0 = the player");
        assertEquals(36, b.readShort(), "the 36 storage slots");
        assertEquals(267, b.readShort(), "slot 0: iron sword id");
        b.readUnsignedByte();                 // count
        b.readShort();                        // meta
        b.readShort();                        // nbt len
        for (int slot = 1; slot < 9; slot++) {
            assertEquals(0, b.readShort(), "air slot " + slot);
        }
        assertEquals(1, b.readShort(), "slot 9: stone id");
        assertEquals(64, b.readUnsignedByte(), "stone count");
        b.readShort();                        // meta
        b.readShort();                        // nbt len
        for (int slot = 10; slot < 36; slot++) {
            assertEquals(0, b.readShort(), "air slot " + slot);
        }
        assertEquals(9, b.readShort(), "hotbar-link count");
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 9, b.readInt(), "hotbar link " + i + " is the identity map + 9");
        }
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void containerSetSlotMatchesPmmpShape() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.containerSetSlot(b, 0, 4, Blocks.state(276, 0), 1); // a diamond sword to slot 4

        assertEquals(Mcpe014Packets.ID_CONTAINER_SET_SLOT, b.readUnsignedByte(), "packet id");
        assertEquals(0, b.readUnsignedByte(), "window id");
        assertEquals(4, b.readShort(), "slot");
        assertEquals(0, b.readShort(), "hotbarSlot (PMMP default)");
        assertEquals(276, b.readShort(), "item id");
        assertEquals(1, b.readUnsignedByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "nbt length");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }
}
