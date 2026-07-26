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
        McpePackets.mobEquipment(b, 42L, Blocks.state(276, 0)); // a diamond sword

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
        McpePackets.mobEquipment(b, 7L, Blocks.AIR);

        ByteBufUtils.readVarInt(b);  // id
        ByteBufUtils.readVarLong(b); // runtime id
        assertEquals(Blocks.AIR, McpeCodec.readItemState(b), "an empty hand serializes as air");
        b.release();
    }

    @Test
    void addItemEntity113CarriesTheItemAndPinsItInPlace() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.addItemEntity(b, 7L, 1.5, 65.0, -2.5, Blocks.state(89, 0)); // floating glowstone

        assertEquals(0x0f, ByteBufUtils.readVarInt(b), "packet id ADD_ITEM_ENTITY");
        long zigzag = ByteBufUtils.readVarLong(b);
        assertEquals(7L, (zigzag >>> 1) ^ -(zigzag & 1), "entity unique id (zigzag-encoded)");
        assertEquals(7L, ByteBufUtils.readVarLong(b), "entity runtime id (plain)");
        assertEquals(Blocks.state(89, 0), McpeCodec.readItemState(b), "the body is the item itself");
        assertEquals(1.5f, b.readFloatLE(), "x");
        assertEquals(65.0f, b.readFloatLE(), "y");
        assertEquals(-2.5f, b.readFloatLE(), "z");
        assertEquals(0f, b.readFloatLE(), "speed x — a prop never moves on its own");
        assertEquals(0f, b.readFloatLE(), "speed y");
        assertEquals(0f, b.readFloatLE(), "speed z");
        assertEquals(1, ByteBufUtils.readVarInt(b), "one metadata entry: the flags");
        b.release();
    }

    @Test
    void addItemEntity014IsBigEndian() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.addItemEntity(b, 7L, 1.5, 65.0, -2.5, Blocks.state(89, 0));

        assertEquals(0x9a, b.readUnsignedByte(), "packet id ADD_ITEM_ENTITY (0.14)");
        assertEquals(7L, b.readLong(), "eid (BE long)");
        assertEquals(89, b.readShort(), "item id (BE short)");
        assertEquals(1, b.readUnsignedByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "nbt length");
        assertEquals(1.5f, b.readFloat(), "x (BE float)");
        assertEquals(65.0f, b.readFloat(), "y");
        assertEquals(-2.5f, b.readFloat(), "z");
        assertEquals(0f, b.readFloat(), "speed x");
        assertEquals(0f, b.readFloat(), "speed y");
        assertEquals(0f, b.readFloat(), "speed z");
        assertFalse(b.isReadable(), "no metadata field at protocol 45 — immobility follows separately");
        b.release();
    }

    @Test
    void mobArmorEquipment113CarriesFourSlotsHeadToFeet() {
        ByteBuf b = Unpooled.buffer();
        // A diamond helmet and iron boots, nothing in between.
        McpePackets.mobArmorEquipment(b, 42L, Blocks.state(310, 0), 0, 0, Blocks.state(309, 0));

        assertEquals(0x20, ByteBufUtils.readVarInt(b), "packet id MOB_ARMOR_EQUIPMENT");
        assertEquals(42L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(Blocks.state(310, 0), McpeCodec.readItemState(b), "slot 0 = helmet");
        assertEquals(0, McpeCodec.readItemState(b), "slot 1 = chestplate (empty)");
        assertEquals(0, McpeCodec.readItemState(b), "slot 2 = leggings (empty)");
        assertEquals(Blocks.state(309, 0), McpeCodec.readItemState(b), "slot 3 = boots");
        assertFalse(b.isReadable(), "exactly four slots, no trailing bytes");
        b.release();
    }

    @Test
    void mobArmorEquipment014IsBigEndian() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.mobArmorEquipment(b, 42L, Blocks.state(310, 0), 0, 0, 0);

        assertEquals(0xa8, b.readUnsignedByte(), "packet id MOB_ARMOR_EQUIPMENT (0.14)");
        assertEquals(42L, b.readLong(), "eid (BE long)");
        assertEquals(310, b.readShort(), "helmet id (BE short)");
        assertEquals(1, b.readUnsignedByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "nbt length");
        assertEquals(0, b.readShort(), "chestplate = air");
        assertEquals(0, b.readShort(), "leggings = air");
        assertEquals(0, b.readShort(), "boots = air");
        assertFalse(b.isReadable(), "exactly four slots, no trailing bytes");
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
