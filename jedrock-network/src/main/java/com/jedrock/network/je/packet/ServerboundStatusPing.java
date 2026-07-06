package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Ping (0x01, STATUS state) for 1.12.2. Carries an opaque {@code long} (usually a
 * timestamp) that the server echoes back in a {@link ClientboundStatusPong}.
 */
public final class ServerboundStatusPing implements ServerboundPacket {

    public static final int PACKET_ID = 0x01;

    public long payload;

    public static ServerboundStatusPing fromBuffer(ByteBuf buf) {
        ServerboundStatusPing p = new ServerboundStatusPing();
        p.payload = buf.readLong();
        return p;
    }

    public int getPacketId() {
        return PACKET_ID;
    }
}
