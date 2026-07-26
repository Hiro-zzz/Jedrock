package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_CONTAINER_SET_CONTENT;
import static com.jedrock.network.pe.McpeProtocol.WINDOW_ID_PLAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the PE 1.1.5 (protocol 113) player-window ContainerSetContent (0x34). The wire
 * shape is PMMP-exact: {@code getSize() + getHotbarSize()} = 45 slots (the 36 storage slots then 9 air),
 * followed by a 9-entry hotbar-link array whose values are {@code index + getHotbarSize()} (= {@code i +
 * 9}). Those two details are what make the client render the hotbar HUD at all — with only 36 slots and
 * no links, mined items filled storage but the hotbar stayed empty (they showed only with the GUI open).
 */
class PePlayerInventoryEncodingTest {

    @Test
    void playerWindowSends45SlotsAndNineHotbarLinks() {
        int stone = Blocks.state(Blocks.STONE, 0);
        int[] states = new int[36];
        int[] counts = new int[36];
        states[0] = stone; counts[0] = 5;      // hotbar slot 0
        states[9] = stone; counts[9] = 64;     // first main slot

        ByteBuf b = Unpooled.buffer();
        McpePackets.playerInventory(b, 1L, slot -> states[slot], slot -> counts[slot]);

        assertEquals(ID_CONTAINER_SET_CONTENT, ByteBufUtils.readVarInt(b), "packet id 0x34");
        assertEquals(WINDOW_ID_PLAYER, ByteBufUtils.readVarInt(b), "window id 0");
        assertEquals(1L, readSignedVarLong(b), "targetEid = the player's own entity id");
        assertEquals(45, ByteBufUtils.readVarInt(b), "slot count = getSize()+getHotbarSize() = 45");

        // Slot 0 = stone(1) count 5. writeSlot packs aux = (meta<<8)|count, then NBT len + two 0 lists.
        assertEquals(Blocks.STONE, ByteBufUtils.readSignedVarInt(b), "slot 0 id = stone");
        assertEquals(5, ByteBufUtils.readSignedVarInt(b) & 0xFF, "slot 0 count = 5");
        assertEquals(0, b.readShortLE(), "slot 0 NBT length");
        assertEquals(0, ByteBufUtils.readVarInt(b), "slot 0 canPlaceOn");
        assertEquals(0, ByteBufUtils.readVarInt(b), "slot 0 canDestroy");

        // Slots 1-8 are empty (air), slot 9 = stone(64); skip the 7 air slots to reach it.
        for (int i = 1; i < 9; i++) {
            assertEquals(Blocks.AIR, ByteBufUtils.readSignedVarInt(b), "slot " + i + " = air");
        }
        assertEquals(Blocks.STONE, ByteBufUtils.readSignedVarInt(b), "slot 9 id = stone");
        assertEquals(64, ByteBufUtils.readSignedVarInt(b) & 0xFF, "slot 9 count = 64");
        b.readShortLE();
        ByteBufUtils.readVarInt(b);
        ByteBufUtils.readVarInt(b);

        // Slots 10-44 are all air (26 remaining storage + 9 trailing hotbar-area).
        for (int i = 10; i < 45; i++) {
            assertEquals(Blocks.AIR, ByteBufUtils.readSignedVarInt(b), "slot " + i + " = air");
        }

        // The hotbar-link array: 9 entries, value i + 9.
        assertEquals(9, ByteBufUtils.readVarInt(b), "hotbar-link count = 9");
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 9, ByteBufUtils.readSignedVarInt(b), "hotbar link " + i + " = i + 9");
        }
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    private static long readSignedVarLong(ByteBuf b) {
        long raw = ByteBufUtils.readVarLong(b);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
