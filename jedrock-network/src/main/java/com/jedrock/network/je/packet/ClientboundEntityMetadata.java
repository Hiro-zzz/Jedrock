package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Entity Metadata (0x3C) for JE 1.12.2, carrying a player avatar's pose. Two entries:
 * index 0 — the shared entity flags byte (crouch / sprint) — and index 6 — the LivingEntity
 * hand-states byte (item-use / eating gesture). The metadata list is a run of {@code index (u8),
 * type (VarInt), value} entries ending with 0xFF.
 */
public final class ClientboundEntityMetadata implements ClientboundPacket {

    /** Shared entity flags (index 0): 0x01 on fire, 0x02 crouched, 0x08 sprinting, 0x20 invisible. */
    public static final int FLAG_CROUCHED = 0x02;
    public static final int FLAG_SPRINTING = 0x08;
    /** Hand-states (index 6): 0x01 = hand active (using an item), 0x02 = which hand. */
    public static final int HAND_ACTIVE = 0x01;

    private static final int INDEX_FLAGS = 0;
    private static final int INDEX_HAND_STATES = 6;
    private static final int TYPE_BYTE = 0;
    private static final int METADATA_END = 0xFF;

    private final int entityId;
    private final int flags;
    private final int handStates;

    private ClientboundEntityMetadata(int entityId, int flags, int handStates) {
        this.entityId = entityId;
        this.flags = flags;
        this.handStates = handStates;
    }

    /** Metadata for a full pose — crouch/sprint flags and the item-use hand state, in one packet. */
    public static ClientboundEntityMetadata pose(int entityId, boolean sneaking, boolean sprinting,
                                                 boolean usingItem) {
        int flags = (sneaking ? FLAG_CROUCHED : 0) | (sprinting ? FLAG_SPRINTING : 0);
        int handStates = usingItem ? HAND_ACTIVE : 0;
        return new ClientboundEntityMetadata(entityId, flags, handStates);
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeByte(INDEX_FLAGS);              // shared flags (crouch / sprint)
        ByteBufUtils.writeVarInt(buf, TYPE_BYTE);
        buf.writeByte(flags);
        buf.writeByte(INDEX_HAND_STATES);        // hand states (item-use gesture)
        ByteBufUtils.writeVarInt(buf, TYPE_BYTE);
        buf.writeByte(handStates);
        buf.writeByte(METADATA_END);             // 0xFF terminates the metadata list
    }

    @Override
    public int getPacketId() {
        return 0x3C;
    }
}
