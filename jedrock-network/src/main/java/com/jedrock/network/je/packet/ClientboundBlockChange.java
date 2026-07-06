package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Block Change (0x0B) for JE 1.12.2 — updates a single block so an edit made by any
 * player becomes visible. The canonical state is already the JE global palette id.
 */
public final class ClientboundBlockChange implements ClientboundPacket {

    private final int x, y, z;
    private final int state;

    public ClientboundBlockChange(int x, int y, int z, int state) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state;
    }

    @Override
    public void write(ByteBuf buf) {
        long pos = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        buf.writeLong(pos);
        // The canonical state (id << 4 | meta) is exactly the JE global palette id.
        ByteBufUtils.writeVarInt(buf, state);
    }

    @Override
    public int getPacketId() {
        return 0x0B;
    }
}
