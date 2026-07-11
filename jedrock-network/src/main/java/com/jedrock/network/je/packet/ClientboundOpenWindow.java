package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Open Window (0x13) for JE 1.12.2 — tells the client to open a container GUI. Body:
 * {@code byte windowId}, {@code string windowType} (e.g. {@code minecraft:chest}), {@code chat title}
 * (JSON), {@code byte slotCount} (the container's own slots, e.g. 27; the player inventory is implied).
 * The horse-only trailing entity id is omitted (chests don't use it).
 */
public final class ClientboundOpenWindow implements ClientboundPacket {

    private final int windowId;
    private final String windowType;
    private final String title;
    private final int slots;

    public ClientboundOpenWindow(int windowId, String windowType, String title, int slots) {
        this.windowId = windowId;
        this.windowType = windowType;
        this.title = title;
        this.slots = slots;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(windowId);
        ByteBufUtils.writeString(buf, windowType);
        ByteBufUtils.writeString(buf, "{\"text\":\"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        buf.writeByte(slots);
    }

    @Override
    public int getPacketId() {
        return 0x13;
    }
}
