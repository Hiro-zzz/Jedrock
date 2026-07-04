package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Player Position (0x0D in PLAY) for JE 1.12.2 — position-only movement update.
 */
public final class ServerboundPlayerPosition implements ServerboundPacket {

    public static final int PACKET_ID = 0x0D;

    public double x, y, z; // y = feet
    public boolean onGround;

    public static ServerboundPlayerPosition fromBuffer(ByteBuf buf) {
        ServerboundPlayerPosition p = new ServerboundPlayerPosition();
        p.x = buf.readDouble();
        p.y = buf.readDouble();
        p.z = buf.readDouble();
        p.onGround = buf.readBoolean();
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
