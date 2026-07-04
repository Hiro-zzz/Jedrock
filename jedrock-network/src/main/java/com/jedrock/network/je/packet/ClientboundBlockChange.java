package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Block Change (0x0B) for JE 1.12.2 — updates a single block so an edit made by any
 * player becomes visible. Maps the canonical block id to the JE global palette state.
 */
public final class ClientboundBlockChange implements ClientboundPacket {

    private final int x, y, z;
    private final int canonicalId;

    public ClientboundBlockChange(int x, int y, int z, int canonicalId) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.canonicalId = canonicalId;
    }

    @Override
    public void write(ByteBuf buf) {
        long pos = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        buf.writeLong(pos);
        // Canonical ids are classic block ids, so the meta-0 global palette state is id << 4.
        ByteBufUtils.writeVarInt(buf, canonicalId << 4);
    }

    @Override
    public int getPacketId() {
        return 0x0B;
    }
}
