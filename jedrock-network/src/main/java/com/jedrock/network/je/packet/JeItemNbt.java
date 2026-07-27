package com.jedrock.network.je.packet;

import com.jedrock.api.item.ItemDisplay;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * The item-name tag as Java Edition wants it: a bare, uncompressed, <b>big-endian named</b> NBT compound
 * written straight into a Slot's trailing NBT field.
 *
 * <pre>
 *   TAG_Compound("")
 *     TAG_Compound("display")
 *       TAG_String("Name") = "§bFrostblade"
 *       TAG_List("Lore") of TAG_String
 * </pre>
 *
 * <p>Only the shape vanilla reads for a renamed item, and nothing else — this server has no enchantments,
 * no unbreakable flag and no attribute modifiers to write.
 *
 * <p><b>Plain strings, not JSON.</b> Text components in {@code Name} arrived in 1.13; both target versions
 * (1.8 and 1.12.2) read a legacy {@code §}-coded string, which is exactly what the unified markup already
 * renders to. So one encoder serves both, and nothing has to know which version it is writing for.
 *
 * <p>Strings here are the ordinary NBT kind — an <b>unsigned short length, big-endian</b> — not the
 * varint-prefixed network strings the Bedrock side uses. Getting those two confused is the classic way to
 * write a compound that reads as garbage.
 */
public final class JeItemNbt {

    private static final int TAG_END = 0x00;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;

    private JeItemNbt() {}

    /**
     * Write the display compound, or a single {@code TAG_End} when there is nothing to say — which is the
     * byte the Slot format has always ended with, so an ordinary item's bytes are unchanged.
     */
    public static void write(ByteBuf buf, ItemDisplay display) {
        if (display == null || display.isEmpty()) {
            buf.writeByte(TAG_END); // "no NBT"
            return;
        }
        buf.writeByte(TAG_COMPOUND);
        writeString(buf, "");            // the root compound is unnamed
        buf.writeByte(TAG_COMPOUND);
        writeString(buf, "display");

        if (!display.name().isEmpty()) {
            buf.writeByte(TAG_STRING);
            writeString(buf, "Name");
            writeString(buf, display.name());
        }
        String[] lore = display.lore();
        if (lore.length > 0) {
            buf.writeByte(TAG_LIST);
            writeString(buf, "Lore");
            buf.writeByte(TAG_STRING);   // the list's element type
            buf.writeInt(lore.length);   // and its length, a big-endian int
            for (String line : lore) {
                writeString(buf, line == null ? "" : line);
            }
        }

        buf.writeByte(TAG_END);          // closes "display"
        buf.writeByte(TAG_END);          // closes the root
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);    // unsigned short, big-endian
        buf.writeBytes(bytes);
    }
}
