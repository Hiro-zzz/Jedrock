package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Pong (0x01, STATUS state) for 1.12.2. Echoes the {@code long} the client sent in its
 * Ping so the multiplayer list can measure latency, then the connection closes.
 */
public final class ClientboundStatusPong implements ClientboundPacket {

    private final long payload;

    public ClientboundStatusPong(long payload) {
        this.payload = payload;
    }

    public void write(ByteBuf buf) {
        buf.writeLong(payload);
    }

    public int getPacketId() {
        return 0x01;
    }
}
