package com.jedrock.network.je.packet;

import com.jedrock.api.item.ItemDisplay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Java Edition item-name tag, byte for byte: a bare big-endian named NBT compound in the Slot's
 * trailing NBT field. Strings are unsigned-short-prefixed here, not varint-prefixed as on the Bedrock
 * side — confusing the two is the classic way to write a compound that reads as garbage.
 */
class JeItemNbtTest {

    private static String readString(ByteBuf b) {
        int length = b.readUnsignedShort();
        byte[] bytes = new byte[length];
        b.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryItemStillWritesTheSingleTagEnd() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, null);

        assertEquals(1, b.readableBytes(), "one byte — exactly what the Slot always ended with");
        assertEquals(0x00, b.readByte());
    }

    @Test
    void anEmptyDisplayIsTreatedAsOrdinary() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, new ItemDisplay("", new String[0]));

        assertEquals(1, b.readableBytes());
        assertEquals(0x00, b.readByte());
    }

    @Test
    void aNameWritesTheDisplayCompound() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, ItemDisplay.of("§bFrostblade"));

        assertEquals(0x0A, b.readByte(), "TAG_Compound (root)");
        assertEquals("", readString(b), "the root is unnamed");
        assertEquals(0x0A, b.readByte(), "TAG_Compound");
        assertEquals("display", readString(b));
        assertEquals(0x08, b.readByte(), "TAG_String");
        assertEquals("Name", readString(b));
        assertEquals("§bFrostblade", readString(b), "a legacy §-coded string, not JSON");
        assertEquals(0x00, b.readByte(), "closes display");
        assertEquals(0x00, b.readByte(), "closes the root");
        assertEquals(0, b.readableBytes());
    }

    @Test
    void loreIsAStringListWithABigEndianIntLength() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, new ItemDisplay("§bFrostblade", new String[]{"§7Cold.", "§8Right-click"}));

        b.readByte();                       // root compound
        readString(b);
        b.readByte();                       // display compound
        readString(b);
        b.readByte();                       // Name
        readString(b);
        readString(b);

        assertEquals(0x09, b.readByte(), "TAG_List");
        assertEquals("Lore", readString(b));
        assertEquals(0x08, b.readByte(), "of TAG_String");
        assertEquals(2, b.readInt(), "a big-endian int length");
        assertEquals("§7Cold.", readString(b));
        assertEquals("§8Right-click", readString(b));
        assertEquals(0x00, b.readByte());
        assertEquals(0x00, b.readByte());
        assertEquals(0, b.readableBytes());
    }

    @Test
    void loreWithoutANameStillWrites() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, new ItemDisplay("", new String[]{"§7Just lore"}));

        b.readByte();
        readString(b);
        b.readByte();
        assertEquals("display", readString(b));
        assertEquals(0x09, b.readByte(), "straight to the list — no Name tag was written");
    }

    @Test
    void anEnchantmentIsAListAtTheRoot() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, ItemDisplay.enchanted(
                com.jedrock.api.item.Enchantments.of(com.jedrock.api.item.Enchantment.SHARPNESS, 3)));

        assertEquals(0x0A, b.readByte(), "root compound");
        assertEquals("", readString(b), "…unnamed");
        assertEquals(0x09, b.readByte(), "TAG_List");
        assertEquals("ench", readString(b), "the name vanilla reads — at the ROOT, not inside display");
        assertEquals(0x0A, b.readByte(), "…of compounds");
        assertEquals(1, b.readInt(), "one entry");
        assertEquals(0x02, b.readByte(), "TAG_Short");
        assertEquals("id", readString(b));
        assertEquals(16, b.readShort(), "sharpness is 16 on JAVA — it is 9 on Bedrock");
        assertEquals(0x02, b.readByte(), "TAG_Short");
        assertEquals("lvl", readString(b));
        assertEquals(3, b.readShort());
        assertEquals(0x00, b.readByte(), "closes the entry");
        assertEquals(0x00, b.readByte(), "closes the root — no display compound, since there is no name");
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void aNamedAndEnchantedItemCarriesBoth() {
        ByteBuf b = Unpooled.buffer();

        JeItemNbt.write(b, new ItemDisplay("§bFrostblade", new String[0],
                com.jedrock.api.item.Enchantments.of(com.jedrock.api.item.Enchantment.UNBREAKING, 1)));

        b.readByte();                       // root compound
        readString(b);
        assertEquals(0x09, b.readByte(), "the enchantments come first…");
        assertEquals("ench", readString(b));
        b.readByte();                       // element type
        b.readInt();                        // count
        b.readByte(); readString(b); assertEquals(34, b.readShort(), "unbreaking, Java's id");
        b.readByte(); readString(b); b.readShort();
        b.readByte();                       // closes the entry
        assertEquals(0x0A, b.readByte(), "…and the display compound after them");
        assertEquals("display", readString(b));
        b.release();
    }
}
