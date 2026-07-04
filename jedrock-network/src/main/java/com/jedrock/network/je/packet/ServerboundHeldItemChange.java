package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Held Item Change (0x1A in PLAY) for JE 1.12.2 — which hotbar slot (0-8) is selected.
 * Tracked so block placement knows which item the player is holding.
 */
public final class ServerboundHeldItemChange implements ServerboundPacket {

    public static final int PACKET_ID = 0x1A;

    public int slot;

    public static ServerboundHeldItemChange fromBuffer(ByteBuf buf) {
        ServerboundHeldItemChange p = new ServerboundHeldItemChange();
        p.slot = buf.readShort();
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
