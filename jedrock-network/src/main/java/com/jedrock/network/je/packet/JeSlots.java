package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * The Java Edition <b>Slot</b> wire format, in one place: {@code short id} ({@code -1} = empty), and when
 * an item is present {@code byte count, short damage, byte 0} (no NBT). Unchanged from 1.8 through 1.12.2,
 * and written by everything that carries an item — window slots, equipment, an item entity's metadata.
 */
final class JeSlots {

    private JeSlots() {}

    /** Write one Slot from a canonical {@code (id << 4) | meta} state; state 0 or count ≤ 0 is empty. */
    static void write(ByteBuf buf, int state, int count) {
        write(buf, state, count, null);
    }

    /** As above, with a custom item's name and lore in the trailing NBT field ({@code null} = plain). */
    static void write(ByteBuf buf, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        if (state == 0 || count <= 0) {
            buf.writeShort(-1);
            return;
        }
        buf.writeShort((state >> 4) & 0xFFFF); // item id
        buf.writeByte(count);
        buf.writeShort(state & 0xF);           // damage = the block meta / variant
        JeItemNbt.write(buf, display);
    }
}
