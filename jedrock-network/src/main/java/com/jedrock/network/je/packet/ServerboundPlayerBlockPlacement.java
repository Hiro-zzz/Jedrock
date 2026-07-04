package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Player Block Placement (0x1F in PLAY) for JE 1.12.2 — right-clicking a block face to
 * place. The new block goes at the clicked position offset by the clicked face; the block type is
 * whatever the player is holding (tracked separately). Cursor/hand fields are ignored.
 */
public final class ServerboundPlayerBlockPlacement implements ServerboundPacket {

    public static final int PACKET_ID = 0x1F;

    /** Offsets per face: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east. */
    public static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    public static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};

    public int x, y, z;
    public int face;

    public static ServerboundPlayerBlockPlacement fromBuffer(ByteBuf buf) {
        ServerboundPlayerBlockPlacement p = new ServerboundPlayerBlockPlacement();
        long pos = buf.readLong();
        p.x = (int) (pos >> 38);
        p.y = (int) ((pos >> 26) & 0xFFF);
        p.z = (int) (pos << 38 >> 38);
        p.face = ByteBufUtils.readVarInt(buf);
        return p;
    }

    /** Position of the block being placed (clicked block + face offset). */
    public int placeX() { return x + (face >= 0 && face < 6 ? FACE_DX[face] : 0); }
    public int placeY() { return y + (face >= 0 && face < 6 ? FACE_DY[face] : 0); }
    public int placeZ() { return z + (face >= 0 && face < 6 ? FACE_DZ[face] : 0); }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
