package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Clientbound Spawn Player (0x05) for JE 1.12.2 — spawns another player's avatar.
 * The client only renders it if the UUID is already present in the tab list
 * (see {@link ClientboundPlayerListItem}), so always add the entry first.
 */
public final class ClientboundSpawnPlayer implements ClientboundPacket {

    private final int entityId;
    private final UUID uuid;
    private final double x, y, z; // y = feet
    private final float yaw, pitch;

    public ClientboundSpawnPlayer(int entityId, UUID uuid,
                                  double x, double y, double z, float yaw, float pitch) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        ByteBufUtils.writeAngle(buf, yaw);
        ByteBufUtils.writeAngle(buf, pitch);
        buf.writeByte(0xFF); // entity metadata: terminator only (all defaults)
    }

    @Override
    public int getPacketId() {
        return 0x05;
    }
}
