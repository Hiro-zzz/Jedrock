package com.jedrock.network.handler.je;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Shared Java Edition inventory-window encoding for the "Window Items" packet, used by both the 1.12.2
 * and 1.8 handlers (same slot layout, only the packet id and total slot count differ).
 *
 * <p>The core tracks a compact 36-slot inventory (0-8 hotbar, 9-35 main). Java's player window (id 0)
 * numbers slots differently — 0-8 are the crafting grid + armor, 9-35 the main inventory, 36-44 the
 * hotbar, 45 the off-hand (1.12.2 only). This maps the core model onto that layout, leaving the
 * crafting / armor / off-hand slots empty.
 */
final class JeInventoryCodec {

    private JeInventoryCodec() {}

    /** Window 0 slot counts: 1.12.2 has an off-hand slot, 1.8 does not. */
    static final int WINDOW_SLOTS_1_12 = 46;
    static final int WINDOW_SLOTS_1_8 = 45;

    /**
     * Write a Window Items body: {@code byte windowId=0}, {@code short count}, then {@code count} slots
     * pulled from the core model (empty where the window slot has no core counterpart). Each core state
     * is a canonical {@code (id << 4) | meta}; the item id is {@code state >> 4} and the damage the meta.
     */
    static void writeWindowItems(ByteBuf buf, int[] states, int[] counts, int totalSlots) {
        buf.writeByte(0);              // window id 0 = the player inventory
        buf.writeShort(totalSlots);
        for (int w = 0; w < totalSlots; w++) {
            int model = modelIndex(w);
            if (model < 0) {
                writeSlot(buf, 0, 0);  // crafting / armor / off-hand — empty
            } else {
                writeSlot(buf, states[model], counts[model]);
            }
        }
    }

    /** Encode a Window Items body to a byte array (for the 1.12.2 typed packet). */
    static byte[] encode(int[] states, int[] counts, int totalSlots) {
        ByteBuf buf = Unpooled.buffer();
        try {
            writeWindowItems(buf, states, counts, totalSlots);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** Core inventory index (0-35) for a Java window-0 slot, or -1 if that window slot isn't backed. */
    private static int modelIndex(int windowSlot) {
        if (windowSlot >= 36 && windowSlot <= 44) {
            return windowSlot - 36;    // hotbar → core 0-8
        }
        if (windowSlot >= 9 && windowSlot <= 35) {
            return windowSlot;         // main inventory → core 9-35 (same index)
        }
        return -1;
    }

    /** Java window-0 slot index for a core inventory slot (0-8 hotbar → 36-44; 9-35 main → 9-35). */
    static int windowSlot(int coreSlot) {
        return coreSlot < 9 ? coreSlot + 36 : coreSlot;
    }

    /** One JE Slot: {@code short id} (-1 = empty), else {@code byte count, short damage, byte 0} (no NBT). */
    static void writeSlot(ByteBuf buf, int state, int count) {
        if (state == 0 || count <= 0) {
            buf.writeShort(-1);
            return;
        }
        buf.writeShort((state >> 4) & 0xFFFF); // item id
        buf.writeByte(count);
        buf.writeShort(state & 0xF);           // damage = block meta
        buf.writeByte(0);                      // no NBT (TAG_End)
    }
}
