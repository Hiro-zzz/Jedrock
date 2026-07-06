package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Creative Inventory Action (0x1B in PLAY) for JE 1.12.2 — sent when a creative player
 * sets a window slot to an item. We read the window slot, the item id and its damage (which doubles
 * as the block metadata for placement); the count and NBT are ignored.
 */
public final class ServerboundCreativeInventoryAction implements ServerboundPacket {

    public static final int PACKET_ID = 0x1B;

    /** Window slot: hotbar is 36..44 (slot 36 = hotbar index 0). */
    public int slot;
    /** Classic item/block id, or -1 for an empty slot. */
    public int itemId;
    /** Item damage = block metadata (variant), 0 when absent. */
    public int damage;

    public static ServerboundCreativeInventoryAction fromBuffer(ByteBuf buf) {
        ServerboundCreativeInventoryAction p = new ServerboundCreativeInventoryAction();
        p.slot = buf.readShort();
        p.itemId = buf.readShort(); // first field of the Slot; -1 = empty
        // A present item is followed by count (byte) + damage (short) + NBT; damage is the meta.
        if (p.itemId != -1 && buf.readableBytes() >= 3) {
            buf.readByte();                     // item count — unused
            p.damage = buf.readShort() & 0xFFFF; // damage = block metadata
        }
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
