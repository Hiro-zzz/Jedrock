package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Entity Action (0x15) for JE 1.12.2 — the client reports a state change on its own
 * player: start/stop sneaking, start/stop sprinting, leave bed, etc. We read the action id (the
 * jump-boost tail is only meaningful for horse jumps and ignored).
 */
public final class ServerboundEntityAction implements ServerboundPacket {

    public static final int PACKET_ID = 0x15;

    public static final int START_SNEAKING = 0;
    public static final int STOP_SNEAKING = 1;
    public static final int START_SPRINTING = 3;
    public static final int STOP_SPRINTING = 4;

    public int entityId;
    public int actionId;

    public static ServerboundEntityAction fromBuffer(ByteBuf buf) {
        ServerboundEntityAction p = new ServerboundEntityAction();
        p.entityId = ByteBufUtils.readVarInt(buf);
        p.actionId = ByteBufUtils.readVarInt(buf);
        // trailing jump-boost VarInt (horse jumps only) is not read
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
