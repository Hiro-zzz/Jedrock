package com.jedrock.network.pe;

import com.jedrock.api.item.ItemDisplay;
import com.jedrock.api.world.Blocks;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol-113 item name on the wire: a <b>network NBT</b> compound (unsigned-varint string lengths,
 * zigzag ints — the same dialect the chest tile uses) inside the Slot's little-endian-short NBT length.
 *
 * <p>The plain case matters as much as the named one: an ordinary item must still write the bare {@code 0}
 * length it always did, so nothing about an ordinary inventory changed on the wire.
 */
class McpeItemNbtEncodingTest {

    private static final int SWORD = 276 << 4;

    private static String readNetworkString(ByteBuf b) {
        int length = ByteBufUtils.readVarInt(b);
        byte[] bytes = new byte[length];
        b.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryItemWritesAZeroLengthNbtFieldAsBefore() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, SWORD, 1);

        assertEquals(276, ByteBufUtils.readSignedVarInt(b), "item id");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "aux = meta << 8 | count");
        assertEquals(0, b.readShortLE(), "no NBT — byte-identical to the wire before custom items");
        assertEquals(0, ByteBufUtils.readVarInt(b), "can place on");
        assertEquals(0, ByteBufUtils.readVarInt(b), "can destroy");
        assertEquals(0, b.readableBytes());
    }

    @Test
    void aNamedItemCarriesALengthPrefixedDisplayCompound() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, SWORD, 1, ItemDisplay.of("§bFrostblade"));

        ByteBufUtils.readSignedVarInt(b); // id
        ByteBufUtils.readSignedVarInt(b); // aux
        int nbtLength = b.readShortLE() & 0xFFFF;
        assertTrue(nbtLength > 0, "the compound was announced");

        ByteBuf nbt = b.readSlice(nbtLength);
        assertEquals(0x0A, nbt.readByte(), "TAG_Compound (root)");
        assertEquals("", readNetworkString(nbt), "unnamed root");
        assertEquals(0x0A, nbt.readByte(), "TAG_Compound");
        assertEquals("display", readNetworkString(nbt));
        assertEquals(0x08, nbt.readByte(), "TAG_String");
        assertEquals("Name", readNetworkString(nbt));
        assertEquals("§bFrostblade", readNetworkString(nbt));
        assertEquals(0x00, nbt.readByte());
        assertEquals(0x00, nbt.readByte());
        assertEquals(0, nbt.readableBytes(), "the announced length matched the compound exactly");

        assertEquals(0, ByteBufUtils.readVarInt(b), "can place on still follows");
        assertEquals(0, ByteBufUtils.readVarInt(b), "and can destroy");
    }

    @Test
    void loreIsAStringListWithAZigzagVarintLength() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, SWORD, 1, new ItemDisplay("§bBlade", new String[]{"§7one", "§7two"}));

        ByteBufUtils.readSignedVarInt(b);
        ByteBufUtils.readSignedVarInt(b);
        ByteBuf nbt = b.readSlice(b.readShortLE() & 0xFFFF);
        nbt.readByte(); readNetworkString(nbt);           // root
        nbt.readByte(); readNetworkString(nbt);           // display
        nbt.readByte(); readNetworkString(nbt); readNetworkString(nbt); // Name

        assertEquals(0x09, nbt.readByte(), "TAG_List");
        assertEquals("Lore", readNetworkString(nbt));
        assertEquals(0x08, nbt.readByte(), "of TAG_String");
        assertEquals(2, ByteBufUtils.readSignedVarInt(nbt), "network NBT: a zigzag varint length");
        assertEquals("§7one", readNetworkString(nbt));
        assertEquals("§7two", readNetworkString(nbt));
    }

    @Test
    void airIsUnchangedAndCarriesNothing() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, Blocks.AIR, 0, ItemDisplay.of("§bIgnored"));

        assertEquals(0, ByteBufUtils.readSignedVarInt(b));
        assertEquals(0, b.readableBytes(), "air carries no further fields, display or not");
    }
}
