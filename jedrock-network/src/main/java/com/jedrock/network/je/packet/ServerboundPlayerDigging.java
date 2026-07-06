package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Player Digging (0x14 in PLAY) for JE 1.12.2 — block break progress.
 *
 * <p>In creative the client breaks instantly and sends {@code status == 0} (started digging);
 * in survival the break completes with {@code status == 2} (finished digging). Either way the
 * block at {@link #x}/{@link #y}/{@link #z} should become air.
 */
public final class ServerboundPlayerDigging implements ServerboundPacket {

    public static final int PACKET_ID = 0x14;

    public static final int STATUS_STARTED = 0;
    public static final int STATUS_FINISHED = 2;
    /** Release use item — finish eating / drinking / blocking / shoot bow. */
    public static final int STATUS_RELEASE_USE = 5;

    public int status;
    public int x, y, z;
    public int face;

    public static ServerboundPlayerDigging fromBuffer(ByteBuf buf) {
        ServerboundPlayerDigging p = new ServerboundPlayerDigging();
        p.status = com.jedrock.utils.ByteBufUtils.readVarInt(buf);
        long pos = buf.readLong();
        // 1.12 packed position: x (26 bits) | y (12 bits) | z (26 bits), signed x/z.
        p.x = (int) (pos >> 38);
        p.y = (int) ((pos >> 26) & 0xFFF);
        p.z = (int) (pos << 38 >> 38);
        p.face = buf.readByte();
        return p;
    }

    /** True when this digging update means the block should be removed (broken). */
    public boolean isBreak() {
        return status == STATUS_STARTED || status == STATUS_FINISHED;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
