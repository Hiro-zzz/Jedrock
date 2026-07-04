package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Creative Inventory Action (0x1B in PLAY) for JE 1.12.2 — sent when a creative player
 * sets a window slot to an item. We read only the window slot and the item id (enough to know what
 * a hotbar slot holds); the rest of the slot data (count, damage, NBT) is ignored.
 */
public final class ServerboundCreativeInventoryAction implements ServerboundPacket {

    public static final int PACKET_ID = 0x1B;

    /** Window slot: hotbar is 36..44 (slot 36 = hotbar index 0). */
    public int slot;
    /** Classic item/block id, or -1 for an empty slot. */
    public int itemId;

    public static ServerboundCreativeInventoryAction fromBuffer(ByteBuf buf) {
        ServerboundCreativeInventoryAction p = new ServerboundCreativeInventoryAction();
        p.slot = buf.readShort();
        p.itemId = buf.readShort(); // first field of the Slot; -1 = empty
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
