package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Click Window (0x07) for JE 1.12.2 — the client clicked a slot in an open window. We read
 * the prefix we act on: window id, slot, button, action number and mode; the trailing clicked-item Slot
 * is ignored (the server is authoritative — it never trusts the client's claim of what's in the slot).
 *
 * <p>{@code mode}: 0 normal (button 0 left / 1 right), 1 shift, 2 number key, 3 middle, 4 drop, 5 drag,
 * 6 double-click. {@code slot} -999 is a click outside the window (drop).
 */
public final class ServerboundClickWindow implements ServerboundPacket {

    public static final int PACKET_ID = 0x07; // 1.12.2 Click Window (0x08 is Close Window)

    public int windowId;
    public int slot;
    public int button;
    public int actionNumber;
    public int mode;

    public static ServerboundClickWindow fromBuffer(ByteBuf buf) {
        ServerboundClickWindow p = new ServerboundClickWindow();
        p.windowId = buf.readUnsignedByte();
        p.slot = buf.readShort();
        p.button = buf.readByte();
        p.actionNumber = buf.readShort();
        p.mode = ByteBufUtils.readVarInt(buf);
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
