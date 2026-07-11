package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Set Slot (0x16) for 1.12.2 — updates one slot of an open window. Jedrock sends it for
 * window 0 (the player inventory) so a survival pickup / consume refreshes the hotbar live.
 */
public final class ClientboundSetSlot implements ClientboundPacket {

    private final int windowId;
    private final int windowSlot;
    private final int state; // canonical (id<<4)|meta, 0 = empty
    private final int count;

    /** Update a slot of the player inventory (window 0). */
    public ClientboundSetSlot(int windowSlot, int state, int count) {
        this(0, windowSlot, state, count);
    }

    /** Update a slot of an arbitrary window ({@code windowId} -1 / {@code windowSlot} -1 = the cursor). */
    public ClientboundSetSlot(int windowId, int windowSlot, int state, int count) {
        this.windowId = windowId;
        this.windowSlot = windowSlot;
        this.state = state;
        this.count = count;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(windowId);
        buf.writeShort(windowSlot);
        if (state == 0 || count <= 0) {
            buf.writeShort(-1);        // empty slot
        } else {
            buf.writeShort((state >> 4) & 0xFFFF); // item id
            buf.writeByte(count);
            buf.writeShort(state & 0xF);           // damage = block meta
            buf.writeByte(0);                      // no NBT
        }
    }

    @Override
    public int getPacketId() {
        return 0x16;
    }
}
