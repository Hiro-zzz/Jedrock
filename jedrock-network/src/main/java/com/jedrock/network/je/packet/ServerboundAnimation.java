package com.jedrock.network.je.packet;

/**
 * Serverbound Animation (0x1D) for JE 1.12.2 — the client swung its arm. The only field is the hand
 * (0 main, 1 off), which we don't need: the swing is relayed as a main-arm swing regardless, so this
 * class just names the packet id.
 */
public final class ServerboundAnimation implements ServerboundPacket {

    public static final int PACKET_ID = 0x1D;

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
