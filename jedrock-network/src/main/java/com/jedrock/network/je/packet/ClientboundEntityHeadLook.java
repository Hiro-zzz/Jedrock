package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Entity Head Look (0x36) for JE 1.12.2. Without it the avatar's head stays
 * frozen relative to the body — Entity Teleport only rotates the body.
 */
public final class ClientboundEntityHeadLook implements ClientboundPacket {

    private final int entityId;
    private final float headYaw;

    public ClientboundEntityHeadLook(int entityId, float headYaw) {
        this.entityId = entityId;
        this.headYaw = headYaw;
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        ByteBufUtils.writeAngle(buf, headYaw);
    }

    @Override
    public int getPacketId() {
        return 0x36;
    }
}
