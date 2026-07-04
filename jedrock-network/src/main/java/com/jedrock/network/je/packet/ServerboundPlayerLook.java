package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Player Look (0x0F in PLAY) for JE 1.12.2 — rotation-only movement update.
 */
public final class ServerboundPlayerLook implements ServerboundPacket {

    public static final int PACKET_ID = 0x0F;

    public float yaw, pitch;
    public boolean onGround;

    public static ServerboundPlayerLook fromBuffer(ByteBuf buf) {
        ServerboundPlayerLook p = new ServerboundPlayerLook();
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
