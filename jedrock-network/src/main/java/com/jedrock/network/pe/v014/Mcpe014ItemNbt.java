package com.jedrock.network.pe.v014;

import com.jedrock.api.item.ItemDisplay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * The item-name tag for protocol 45 — the third NBT dialect this server has to speak, and the plainest:
 * <b>little-endian NBT</b>, with an LE-short string length and LE ints. 0.14 predates the varint network
 * NBT that 1.1.5 uses, so this is genuinely a different encoder rather than a parameter on one.
 *
 * <pre>
 *   TAG_Compound("")
 *     TAG_Compound("display")
 *       TAG_String("Name")
 *       TAG_List("Lore") of TAG_String
 * </pre>
 *
 * <p>Like 1.1.5, the Slot carries its NBT length-prefixed (an LE short), so the compound is built into a
 * scratch buffer first — and like 1.1.5, an item with no name writes the plain {@code 0} it always did.
 */
final class Mcpe014ItemNbt {

    private static final int TAG_END = 0x00;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;

    private Mcpe014ItemNbt() {}

    /** Write the Slot's NBT field: an LE-short length, then that many bytes of compound. */
    static void writeSlotNbt(ByteBuf b, ItemDisplay display) {
        if (display == null || display.isEmpty()) {
            b.writeShort(0); // 0.14 slots are big-endian shorts elsewhere; the length here is LE-shaped
            return;
        }
        ByteBuf nbt = Unpooled.buffer(64);
        try {
            writeCompound(nbt, display);
            b.writeShortLE(nbt.readableBytes());
            b.writeBytes(nbt);
        } finally {
            nbt.release();
        }
    }

    private static void writeCompound(ByteBuf b, ItemDisplay display) {
        b.writeByte(TAG_COMPOUND);
        writeString(b, "");
        b.writeByte(TAG_COMPOUND);
        writeString(b, "display");

        if (!display.name().isEmpty()) {
            b.writeByte(TAG_STRING);
            writeString(b, "Name");
            writeString(b, display.name());
        }
        String[] lore = display.lore();
        if (lore.length > 0) {
            b.writeByte(TAG_LIST);
            writeString(b, "Lore");
            b.writeByte(TAG_STRING);   // element type
            b.writeIntLE(lore.length); // little-endian NBT: an LE int length
            for (String line : lore) {
                writeString(b, line == null ? "" : line);
            }
        }

        b.writeByte(TAG_END);
        b.writeByte(TAG_END);
    }

    private static void writeString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        b.writeShortLE(bytes.length);  // little-endian NBT: an LE-short length
        b.writeBytes(bytes);
    }
}
