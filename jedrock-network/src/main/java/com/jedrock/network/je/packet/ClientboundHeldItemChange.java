package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Held Item Change (0x3A) for 1.12.2.
 */
public final class ClientboundHeldItemChange implements ClientboundPacket {

    public byte slot; // 0-8

    public ClientboundHeldItemChange() {
        this.slot = 0;
    }

    public void write(ByteBuf buf) {
        buf.writeByte(slot);
    }

    public int getPacketId() {
        return 0x3A;
    }
}
