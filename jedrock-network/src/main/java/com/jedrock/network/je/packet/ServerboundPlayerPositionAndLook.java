package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Player Position And Look (0x0E in PLAY) for JE 1.12.2.
 */
public final class ServerboundPlayerPositionAndLook implements ServerboundPacket {

    public static final int PACKET_ID = 0x0E;

    public double x, y, z; // y = feet
    public float yaw, pitch;
    public boolean onGround;

    public static ServerboundPlayerPositionAndLook fromBuffer(ByteBuf buf) {
        ServerboundPlayerPositionAndLook p = new ServerboundPlayerPositionAndLook();
        p.x = buf.readDouble();
        p.y = buf.readDouble();
        p.z = buf.readDouble();
        p.yaw = buf.readFloat();
        p.pitch = buf.readFloat();
        p.onGround = buf.readBoolean();
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
