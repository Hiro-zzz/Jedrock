package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Destroy Entities (0x32) for JE 1.12.2 — despawns entities (player avatars).
 */
public final class ClientboundDestroyEntities implements ClientboundPacket {

    private final int entityId;

    public ClientboundDestroyEntities(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, 1); // entity count
        ByteBufUtils.writeVarInt(buf, entityId);
    }

    @Override
    public int getPacketId() {
        return 0x32;
    }
}
