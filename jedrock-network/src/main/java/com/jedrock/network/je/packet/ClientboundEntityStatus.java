package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Entity Status (0x1B) for JE 1.12.2 — plays a one-shot status event on an entity. We use
 * it for {@link #STATUS_HURT} (a living entity took damage: the red flash + hurt sound). The entity id
 * is a plain big-endian int here (not a VarInt, unlike most 1.12.2 packets).
 */
public final class ClientboundEntityStatus implements ClientboundPacket {

    /** Entity status 2: a living entity is hurt (damage animation + sound). */
    public static final byte STATUS_HURT = 2;

    private final int entityId;
    private final byte status;

    public ClientboundEntityStatus(int entityId, byte status) {
        this.entityId = entityId;
        this.status = status;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeInt(entityId);   // plain int32, not a VarInt
        buf.writeByte(status);
    }

    @Override
    public int getPacketId() {
        return 0x1B;
    }
}
