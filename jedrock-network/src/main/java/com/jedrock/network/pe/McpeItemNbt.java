package com.jedrock.network.pe;

import com.jedrock.api.item.ItemDisplay;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * The item-name tag for protocol 113, in <b>network NBT</b> — the same dialect the chest tile already uses
 * ({@link McpeCodec#writeChestTile}): unsigned-varint string lengths and zigzag-varint ints, whatever the
 * "little-endian" label on PocketMine's stream suggests. Its {@code write(true)} forces exactly this.
 *
 * <pre>
 *   TAG_Compound("")
 *     TAG_Compound("display")
 *       TAG_String("Name")
 *       TAG_List("Lore") of TAG_String
 * </pre>
 *
 * <p>The one structural difference from the Java side is that a Bedrock Slot carries its NBT
 * <b>length-prefixed</b> (a little-endian short), so the compound has to be built before it can be
 * announced. It is written into a scratch buffer and copied — a cost paid only by a stack that actually
 * has a name, since an ordinary one still writes the plain {@code 0} length it always did.
 */
final class McpeItemNbt {

    private static final int TAG_END = 0x00;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;

    private McpeItemNbt() {}

    /**
     * Write the Slot's NBT field: a little-endian short length, then that many bytes of compound. An
     * absent or empty display writes {@code 0} — byte-identical to what this server sent before custom
     * items existed.
     */
    static void writeSlotNbt(ByteBuf b, ItemDisplay display) {
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
            b.writeByte(TAG_STRING);                        // element type
            ByteBufUtils.writeSignedVarInt(b, lore.length);  // network NBT: a zigzag varint length
            for (String line : lore) {
                writeString(b, line == null ? "" : line);
            }
        }

        b.writeByte(TAG_END);            // closes "display"
        b.writeByte(TAG_END);            // closes the root
    }

    private static void writeString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBufUtils.writeVarInt(b, bytes.length); // network NBT: unsigned-varint length
        b.writeBytes(bytes);
    }
}
