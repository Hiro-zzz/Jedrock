package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Entity Metadata (0x3C) for JE 1.12.2. We only need index 0 — the shared entity
 * bit-flags byte — to toggle the crouch ({@link #FLAG_CROUCHED}) pose on a player avatar. The
 * metadata list is a run of {@code index (u8), type (VarInt), value} entries ending with 0xFF.
 */
public final class ClientboundEntityMetadata implements ClientboundPacket {

    /** Shared entity flags (index 0): 0x01 on fire, 0x02 crouched, 0x08 sprinting, 0x20 invisible. */
    public static final int FLAG_CROUCHED = 0x02;
    public static final int FLAG_SPRINTING = 0x08;

    private static final int INDEX_FLAGS = 0;
    private static final int TYPE_BYTE = 0;
    private static final int METADATA_END = 0xFF;

    private final int entityId;
    private final int flags;

    public ClientboundEntityMetadata(int entityId, int flags) {
        this.entityId = entityId;
        this.flags = flags;
    }

    /** Metadata carrying the pose flags (crouch and/or sprint) — both sent together in the one byte. */
    public static ClientboundEntityMetadata pose(int entityId, boolean sneaking, boolean sprinting) {
        int flags = (sneaking ? FLAG_CROUCHED : 0) | (sprinting ? FLAG_SPRINTING : 0);
        return new ClientboundEntityMetadata(entityId, flags);
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeByte(INDEX_FLAGS);            // metadata index 0 = shared flags
        ByteBufUtils.writeVarInt(buf, TYPE_BYTE);
        buf.writeByte(flags);
        buf.writeByte(METADATA_END);           // 0xFF terminates the metadata list
    }

    @Override
    public int getPacketId() {
        return 0x3C;
    }
}
