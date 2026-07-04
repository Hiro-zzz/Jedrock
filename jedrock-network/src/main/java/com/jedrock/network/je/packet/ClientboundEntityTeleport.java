package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Entity Teleport (0x4C) for JE 1.12.2 — absolute position update for an
 * entity. Used to relay other players' movement; the client interpolates short hops,
 * so teleports at the sender's own rate look smooth.
 */
public final class ClientboundEntityTeleport implements ClientboundPacket {

    private final int entityId;
    private final double x, y, z; // y = feet
    private final float yaw, pitch;

    public ClientboundEntityTeleport(int entityId, double x, double y, double z, float yaw, float pitch) {
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        ByteBufUtils.writeAngle(buf, yaw);
        ByteBufUtils.writeAngle(buf, pitch);
        buf.writeBoolean(true); // on ground
    }

    @Override
    public int getPacketId() {
        return 0x4C;
    }
}
