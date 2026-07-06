package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Animation (0x06) for JE 1.12.2 — plays a one-shot animation on an entity. We use it
 * for the arm swing ({@link #SWING_MAIN_ARM}) when a player attacks / digs / interacts.
 */
public final class ClientboundAnimation implements ClientboundPacket {

    /** Animation ids: 0 swing main arm, 1 take damage, 2 leave bed, 3 swing offhand, 4/5 crit. */
    public static final int SWING_MAIN_ARM = 0;

    private final int entityId;
    private final int animation;

    public ClientboundAnimation(int entityId, int animation) {
        this.entityId = entityId;
        this.animation = animation;
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeByte(animation);
    }

    @Override
    public int getPacketId() {
        return 0x06;
    }
}
