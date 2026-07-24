package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.network.pe.v014.Mcpe014Packets;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the held-item visual, verbatim from PMMP {@code MobEquipmentPacket}: protocol
 * 113 (0x1f — varint id, runtime id, slot, then <b>three</b> trailing bytes incl. windowId) and the
 * 0.14 era (0xa7 — big-endian eid, slot, then only <b>two</b> trailing bytes; the era predates windowId).
 */
class PeHeldItemEncodingTest {

    @Test
    void mobEquipment113MatchesPmmp() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeMobEquipment(b, 42L, Blocks.state(276, 0)); // a diamond sword

        assertEquals(0x1F, ByteBufUtils.readVarInt(b), "packet id MOB_EQUIPMENT");
        assertEquals(42L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(Blocks.state(276, 0), McpeCodec.readItemState(b), "the item in hand");
        assertEquals(0, b.readByte(), "inventorySlot");
        assertEquals(0, b.readByte(), "hotbarSlot");
        assertEquals(0, b.readByte(), "windowId");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void emptyHandIs113Air() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeMobEquipment(b, 7L, Blocks.AIR);

        ByteBufUtils.readVarInt(b);  // id
        ByteBufUtils.readVarLong(b); // runtime id
        assertEquals(Blocks.AIR, McpeCodec.readItemState(b), "an empty hand serializes as air");
        b.release();
    }

    @Test
    void mobEquipment014IsBigEndianWithoutWindowId() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.mobEquipment(b, 42L, Blocks.state(267, 0)); // an iron sword

        assertEquals(0xa7, b.readUnsignedByte(), "packet id MOB_EQUIPMENT (0.14)");
        assertEquals(42L, b.readLong(), "eid (BE long)");
        assertEquals(267, b.readShort(), "item id (BE short)");
        assertEquals(1, b.readUnsignedByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "nbt length");
        assertEquals(0, b.readByte(), "slot");
        assertEquals(0, b.readByte(), "selectedSlot");
        assertFalse(b.isReadable(), "no windowId byte at protocol 45");
        b.release();
    }
}
