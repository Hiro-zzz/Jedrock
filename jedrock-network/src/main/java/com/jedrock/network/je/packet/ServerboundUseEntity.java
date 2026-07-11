package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Use Entity — the client interacts with or attacks another entity. Packet id 0x0A on
 * 1.12.2; 1.8 sends the same body under id 0x02. Body: {@code target} (VarInt entity id),
 * {@code type} (VarInt: 0 interact, 1 attack, 2 interact-at). We read only that prefix — the trailing
 * interact-at floats / 1.12.2 hand VarInt are irrelevant to the attack we care about, so they're left
 * unread in the buffer.
 */
public final class ServerboundUseEntity implements ServerboundPacket {

    public static final int PACKET_ID = 0x0A; // 1.12.2; 1.8 dispatches the same body from its 0x02

    public static final int TYPE_INTERACT = 0;
    public static final int TYPE_ATTACK = 1;
    public static final int TYPE_INTERACT_AT = 2;

    public int target;
    public int type;

    public static ServerboundUseEntity fromBuffer(ByteBuf buf) {
        ServerboundUseEntity p = new ServerboundUseEntity();
        p.target = ByteBufUtils.readVarInt(buf);
        p.type = ByteBufUtils.readVarInt(buf);
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
