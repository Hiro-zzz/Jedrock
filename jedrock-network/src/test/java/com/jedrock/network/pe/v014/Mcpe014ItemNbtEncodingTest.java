package com.jedrock.network.pe.v014;

import com.jedrock.api.item.ItemDisplay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol-45 item name: the third NBT dialect this server speaks, and the plainest — <b>little-endian
 * NBT</b>, with LE-short string lengths and LE int list lengths. 0.14 predates the varint network NBT that
 * 1.1.5 uses, so this is genuinely a different encoder and not a flag on one.
 */
class Mcpe014ItemNbtEncodingTest {

    private static final int SWORD = 276 << 4;

    private static String readLeString(ByteBuf b) {
        int length = b.readShortLE() & 0xFFFF;
        byte[] bytes = new byte[length];
        b.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryItemWritesAZeroNbtLengthAsBefore() {
        ByteBuf b = Unpooled.buffer();

        Mcpe014Packets.writeSlot(b, SWORD, 3);

        assertEquals(276, b.readShort(), "id (big-endian, as 0.14 slots are)");
        assertEquals(3, b.readByte(), "count");
        assertEquals(0, b.readShort(), "meta");
        assertEquals(0, b.readShort(), "no NBT — unchanged from before custom items");
        assertEquals(0, b.readableBytes());
    }

    @Test
    void aNamedItemCarriesALengthPrefixedLittleEndianCompound() {
        ByteBuf b = Unpooled.buffer();

        Mcpe014Packets.writeSlot(b, SWORD, 1, ItemDisplay.of("§bFrostblade"));

        b.readShort();  // id
        b.readByte();   // count
        b.readShort();  // meta
        int nbtLength = b.readShortLE() & 0xFFFF;
        assertTrue(nbtLength > 0);

        ByteBuf nbt = b.readSlice(nbtLength);
        assertEquals(0x0A, nbt.readByte(), "TAG_Compound (root)");
        assertEquals("", readLeString(nbt), "unnamed root");
        assertEquals(0x0A, nbt.readByte());
        assertEquals("display", readLeString(nbt));
        assertEquals(0x08, nbt.readByte(), "TAG_String");
        assertEquals("Name", readLeString(nbt));
        assertEquals("§bFrostblade", readLeString(nbt));
        assertEquals(0x00, nbt.readByte());
        assertEquals(0x00, nbt.readByte());
        assertEquals(0, nbt.readableBytes(), "the announced length matched the compound exactly");
    }

    @Test
    void loreIsAStringListWithALittleEndianIntLength() {
        ByteBuf b = Unpooled.buffer();

        Mcpe014Packets.writeSlot(b, SWORD, 1, new ItemDisplay("§bBlade", new String[]{"§7one"}));

        b.readShort(); b.readByte(); b.readShort();
        ByteBuf nbt = b.readSlice(b.readShortLE() & 0xFFFF);
        nbt.readByte(); readLeString(nbt);                     // root
        nbt.readByte(); readLeString(nbt);                     // display
        nbt.readByte(); readLeString(nbt); readLeString(nbt);  // Name

        assertEquals(0x09, nbt.readByte(), "TAG_List");
        assertEquals("Lore", readLeString(nbt));
        assertEquals(0x08, nbt.readByte(), "of TAG_String");
        assertEquals(1, nbt.readIntLE(), "little-endian NBT: an LE int length");
        assertEquals("§7one", readLeString(nbt));
    }

    @Test
    void airIgnoresADisplayEntirely() {
        ByteBuf b = Unpooled.buffer();

        Mcpe014Packets.writeSlot(b, 0, 0, ItemDisplay.of("§bIgnored"));

        assertEquals(0, b.readShort(), "air");
        assertEquals(0, b.readableBytes());
    }
}
