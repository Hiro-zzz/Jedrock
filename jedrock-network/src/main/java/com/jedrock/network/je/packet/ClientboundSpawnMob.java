package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Clientbound Spawn Mob (0x03) for JE 1.12.2 — spawns a non-player entity (a puppet). Unlike a player
 * avatar this needs no tab-list entry; the client renders it straight from the numeric {@code type} id
 * (the classic pre-1.13 mob id). Position is doubles, rotation is angle-bytes, and the metadata is an
 * empty {@code 0xff} terminator (all defaults) — a static visual with no simulated state.
 */
public final class ClientboundSpawnMob implements ClientboundPacket {

    private final int entityId;
    private final UUID uuid;
    private final int type;
    private final double x, y, z; // y = feet
    private final float yaw, pitch;

    public ClientboundSpawnMob(int entityId, UUID uuid, int type,
                               double x, double y, double z, float yaw, float pitch) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.type = type;
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
        ByteBufUtils.writeVarInt(buf, type);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        ByteBufUtils.writeAngle(buf, yaw);   // yaw
        ByteBufUtils.writeAngle(buf, pitch); // pitch
        ByteBufUtils.writeAngle(buf, yaw);   // head pitch
        buf.writeShort(0);                   // velocity x
        buf.writeShort(0);                   // velocity y
        buf.writeShort(0);                   // velocity z
        buf.writeByte(0xFF);                 // entity metadata: terminator only (all defaults)
    }

    @Override
    public int getPacketId() {
        return 0x03;
    }
}
