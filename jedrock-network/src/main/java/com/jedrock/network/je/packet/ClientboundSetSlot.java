package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Set Slot (0x16) for 1.12.2 — updates one slot of an open window. Jedrock sends it for
 * window 0 (the player inventory) so a survival pickup / consume refreshes the hotbar live.
 */
public final class ClientboundSetSlot implements ClientboundPacket {

    private final int windowSlot;
    private final int state; // canonical (id<<4)|meta, 0 = empty
    private final int count;

    public ClientboundSetSlot(int windowSlot, int state, int count) {
        this.windowSlot = windowSlot;
        this.state = state;
        this.count = count;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(0);              // window id 0 = the player inventory
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
