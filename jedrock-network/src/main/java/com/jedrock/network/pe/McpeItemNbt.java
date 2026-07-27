package com.jedrock.network.pe;

import com.jedrock.api.item.ItemDisplay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * The item-name tag for <b>both</b> Bedrock eras — 1.1.5 and 0.14 alike — in <b>little-endian NBT</b>:
 * LE-short string lengths, LE ints, length-prefixed by the Slot's own LE short.
 *
 * <pre>
 *   TAG_Compound("")
 *     TAG_Compound("display")
 *       TAG_String("Name")
 *       TAG_List("Lore") of TAG_String
 * </pre>
 *
 * <h2>Not the dialect the chunk tiles use — the distinction that cost a client test</h2>
 *
 * <p>Protocol 113 speaks two NBT dialects and the choice is per <em>call site</em>, not per protocol.
 * A chunk's block-entity tail goes through PocketMine's {@code write(TRUE)}, which forces <b>network</b>
 * NBT (unsigned-varint string lengths, zigzag ints) — that is what {@link McpeCodec#writeChestTile} writes.
 * An <b>item's</b> NBT does not: {@code Item::writeCompoundTag} builds it with
 * {@code new NBT(NBT::LITTLE_ENDIAN)} and plain {@code write()}, so it stays little-endian.
 *
 * <p>Written the network way first, the retail 1.1.5 client did not complain — it simply ignored the
 * compound and went on showing the vanilla item name, which is the quietest possible failure and the
 * reason this comment exists.
 *
 * <p>Since 0.14 is little-endian too, one encoder now serves both eras: the same bytes, for once.
 */
public final class McpeItemNbt {

    private static final int TAG_END = 0x00;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;

    private McpeItemNbt() {}

    /**
     * Write the Slot's NBT field: an LE-short length, then that many bytes of compound. An absent or empty
     * display writes {@code 0} — byte-identical to what this server sent before custom items existed.
     */
    public static void writeSlotNbt(ByteBuf b, ItemDisplay display) {
        if (display == null || display.isEmpty()) {
            b.writeShortLE(0);
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
        writeString(b, "");              // the root compound is unnamed
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
            b.writeByte(TAG_STRING);   // the list's element type
            b.writeIntLE(lore.length); // little-endian NBT: an LE int length
            for (String line : lore) {
                writeString(b, line == null ? "" : line);
            }
        }

        b.writeByte(TAG_END);            // closes "display"
        b.writeByte(TAG_END);            // closes the root
    }

    private static void writeString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        b.writeShortLE(bytes.length);    // little-endian NBT: an LE-short length
        b.writeBytes(bytes);
    }
}
