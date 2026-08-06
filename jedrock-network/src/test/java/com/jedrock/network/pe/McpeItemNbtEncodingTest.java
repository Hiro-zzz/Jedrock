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
 * The Bedrock item name on the wire — <b>little-endian</b> NBT behind the Slot's LE-short length, on both
 * eras. Written the network way (varint strings, zigzag ints) first, which is the dialect the chunk tiles
 * use; the retail 1.1.5 client answered by silently ignoring the compound and showing the vanilla name, so
 * these assertions are deliberately about the exact encoding rather than "some NBT is present".
 *
 * <p>The plain case matters as much as the named one: an ordinary item must still write the bare {@code 0}
 * length it always did, so nothing about an ordinary inventory changed on the wire.
 */
class McpeItemNbtEncodingTest {

    private static final int SWORD = 276 << 4;

    /** Little-endian NBT string: an LE-short length, then the bytes. */
    private static String readLeString(ByteBuf b) {
        int length = b.readShortLE() & 0xFFFF;
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
    void aNamedItemCarriesALengthPrefixedLittleEndianCompound() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, SWORD, 1, ItemDisplay.of("§bFrostblade"));

        ByteBufUtils.readSignedVarInt(b); // id
        ByteBufUtils.readSignedVarInt(b); // aux
        int nbtLength = b.readShortLE() & 0xFFFF;
        assertTrue(nbtLength > 0, "the compound was announced");

        ByteBuf nbt = b.readSlice(nbtLength);
        assertEquals(0x0A, nbt.readByte(), "TAG_Compound (root)");
        assertEquals("", readLeString(nbt), "unnamed root");
        assertEquals(0x0A, nbt.readByte(), "TAG_Compound");
        assertEquals("display", readLeString(nbt));
        assertEquals(0x08, nbt.readByte(), "TAG_String");
        assertEquals("Name", readLeString(nbt));
        assertEquals("§bFrostblade", readLeString(nbt));
        assertEquals(0x00, nbt.readByte());
        assertEquals(0x00, nbt.readByte());
        assertEquals(0, nbt.readableBytes(), "the announced length matched the compound exactly");

        assertEquals(0, ByteBufUtils.readVarInt(b), "can place on still follows");
        assertEquals(0, ByteBufUtils.readVarInt(b), "and can destroy");
    }

    @Test
    void loreIsAStringListWithALittleEndianIntLength() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, SWORD, 1,
                new ItemDisplay("§bBlade", new String[]{"§7one", "§7two"}));

        ByteBufUtils.readSignedVarInt(b);
        ByteBufUtils.readSignedVarInt(b);
        ByteBuf nbt = b.readSlice(b.readShortLE() & 0xFFFF);
        nbt.readByte(); readLeString(nbt);                     // root
        nbt.readByte(); readLeString(nbt);                     // display
        nbt.readByte(); readLeString(nbt); readLeString(nbt);  // Name

        assertEquals(0x09, nbt.readByte(), "TAG_List");
        assertEquals("Lore", readLeString(nbt));
        assertEquals(0x08, nbt.readByte(), "of TAG_String");
        assertEquals(2, nbt.readIntLE(), "little-endian NBT: an LE int length, not a zigzag varint");
        assertEquals("§7one", readLeString(nbt));
        assertEquals("§7two", readLeString(nbt));
    }

    @Test
    void theZeroFourteenSlotWritesTheSameCompound() {
        ByteBuf b = Unpooled.buffer();

        com.jedrock.network.pe.v014.Mcpe014Packets.writeSlot(b, SWORD, 1,
                ItemDisplay.of("§bFrostblade"));

        assertEquals(276, b.readShort(), "id (big-endian, as 0.14 slots are)");
        assertEquals(1, b.readByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        ByteBuf nbt = b.readSlice(b.readShortLE() & 0xFFFF);
        assertEquals(0x0A, nbt.readByte());
        assertEquals("", readLeString(nbt));
        assertEquals(0x0A, nbt.readByte());
        assertEquals("display", readLeString(nbt), "one encoder, both eras");
    }

    @Test
    void airIsUnchangedAndCarriesNothing() {
        ByteBuf b = Unpooled.buffer();

        McpeCodec.writeSlot(b, Blocks.AIR, 0, ItemDisplay.of("§bIgnored"));

        assertEquals(0, ByteBufUtils.readSignedVarInt(b));
        assertEquals(0, b.readableBytes(), "air carries no further fields, display or not");
    }

    @Test
    void anEnchantmentUsesBedrocksOwnIdAndLittleEndianShorts() {
        ByteBuf b = Unpooled.buffer();

        McpeItemNbt.writeSlotNbt(b, ItemDisplay.enchanted(
                com.jedrock.api.item.Enchantments.of(com.jedrock.api.item.Enchantment.SHARPNESS, 2)));

        int length = b.readShortLE() & 0xFFFF;
        assertTrue(length > 0, "the compound is length-prefixed by the Slot's own LE short");
        assertEquals(0x0A, b.readByte(), "root compound");
        assertEquals("", readLeString(b), "…unnamed");
        assertEquals(0x09, b.readByte(), "TAG_List");
        assertEquals("ench", readLeString(b), "at the root, beside display — not inside it");
        assertEquals(0x0A, b.readByte(), "…of compounds");
        assertEquals(1, b.readIntLE(), "one entry, LE like everything else in this dialect");
        assertEquals(0x02, b.readByte());
        assertEquals("id", readLeString(b));
        assertEquals(9, b.readShortLE(), "sharpness is 9 on BEDROCK — it is 16 on Java");
        assertEquals(0x02, b.readByte());
        assertEquals("lvl", readLeString(b));
        assertEquals(2, b.readShortLE());
        assertEquals(0x00, b.readByte(), "closes the entry");
        assertEquals(0x00, b.readByte(), "closes the root");
        b.release();
    }

    @Test
    void anOrdinaryStackIsUnchangedByEnchantmentsExisting() {
        ByteBuf b = Unpooled.buffer();

        McpeItemNbt.writeSlotNbt(b, null, true);

        assertEquals(2, b.readableBytes(), "the bare LE-short zero, exactly as before");
        assertEquals(0, b.readShortLE());
        b.release();
    }
}
