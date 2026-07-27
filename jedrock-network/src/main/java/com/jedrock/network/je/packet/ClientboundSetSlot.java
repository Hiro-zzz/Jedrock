package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Set Slot (0x16) for 1.12.2 — updates one slot of an open window. Jedrock sends it for
 * window 0 (the player inventory) so a survival pickup / consume refreshes the hotbar live.
 */
public final class ClientboundSetSlot implements ClientboundPacket {

    private final int windowId;
    private final int windowSlot;
    private final int state; // canonical (id<<4)|meta, 0 = empty
    private final int count;
    /** A custom item's name and lore for this slot, or {@code null} for an ordinary item. */
    private final com.jedrock.api.item.ItemDisplay display;

    /** Update a slot of the player inventory (window 0). */
    public ClientboundSetSlot(int windowSlot, int state, int count) {
        this(0, windowSlot, state, count, null);
    }

    /** Update a player-inventory slot showing a custom item's name and lore. */
    public ClientboundSetSlot(int windowSlot, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        this(0, windowSlot, state, count, display);
    }

    /** Update a slot of an arbitrary window ({@code windowId} -1 / {@code windowSlot} -1 = the cursor). */
    public ClientboundSetSlot(int windowId, int windowSlot, int state, int count) {
        this(windowId, windowSlot, state, count, null);
    }

    public ClientboundSetSlot(int windowId, int windowSlot, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        this.windowId = windowId;
        this.windowSlot = windowSlot;
        this.state = state;
        this.count = count;
        this.display = display;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(windowId);
        buf.writeShort(windowSlot);
        JeSlots.write(buf, state, count, display);
    }

    @Override
    public int getPacketId() {
        return 0x16;
    }
}
