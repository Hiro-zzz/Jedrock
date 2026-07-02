package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Player Position And Look (0x2F) for 1.12.2.
 * This spawns/teleports the player in the world.
 * After sending this, the client usually sends back a teleport confirm.
 */
public final class ClientboundPlayerPositionAndLook implements ClientboundPacket {

    public double x, y, z;
    public float yaw, pitch;
    public int flags;           // bitfield: 0x01 x relative, etc. Usually 0 for absolute.
    public int teleportId;

    public ClientboundPlayerPositionAndLook() {
        // Spawn at reasonable height
        this.x = 0.5;
        this.y = 64;
        this.z = 0.5;
        this.yaw = 0f;
        this.pitch = 0f;
        this.flags = 0;
        this.teleportId = 1;
    }

    public void write(ByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeByte(flags);
        ByteBufUtils.writeVarInt(buf, teleportId);
    }

    public int getPacketId() {
        return 0x2F;
    }
}
