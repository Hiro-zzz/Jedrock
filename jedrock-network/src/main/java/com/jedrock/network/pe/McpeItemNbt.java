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
    private static final int TAG_SHORT = 0x02;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;

    /**
     * The escape hatch for the one thing on this wire that has failed quietly before. Item NBT on a
     * Bedrock client is where a wrong dialect once cost a client test, and enchantments are the newest
     * thing written into it — so {@code -Djedrock.pe.enchantNbt=false} stops writing them at all, leaving
     * the exact bytes that were already client-verified. Same shape as {@code jedrock.pe.changeDimension}.
     */
    private static final boolean ENCHANT_NBT =
            !"false".equalsIgnoreCase(System.getProperty("jedrock.pe.enchantNbt", "true"));

    private McpeItemNbt() {}

    /**
     * Write the Slot's NBT field for a <b>1.1.5</b> client: an LE-short length, then that many bytes of
     * compound. An absent or empty display writes {@code 0} — byte-identical to what this server sent
     * before custom items existed.
     */
    public static void writeSlotNbt(ByteBuf b, ItemDisplay display) {
        writeSlotNbt(b, display, false);
    }

    /**
     * As {@link #writeSlotNbt(ByteBuf, ItemDisplay)}, but {@code era014} says this is going to an 0.14
     * client, whose enchantment table stops at id 24 — anything above is left out rather than sent to a
     * client that has no placeholder for what it doesn't know. The canonical set doesn't currently reach
     * that far, so this is a guard for the day somebody widens it, not a filter that fires today.
     */
    public static void writeSlotNbt(ByteBuf b, ItemDisplay display, boolean era014) {
        if (display == null || display.isEmpty()) {
            b.writeShortLE(0);
            return;
        }
        ByteBuf nbt = Unpooled.buffer(64);
        try {
            writeCompound(nbt, display, era014);
            b.writeShortLE(nbt.readableBytes());
            b.writeBytes(nbt);
        } finally {
            nbt.release();
        }
    }

    private static void writeCompound(ByteBuf b, ItemDisplay display, boolean era014) {
        b.writeByte(TAG_COMPOUND);
        writeString(b, "");              // the root compound is unnamed

        writeEnchantments(b, display.enchantments(), era014);

        // As on the Java side: only open "display" when there is something to put in it, so an enchanted
        // but unnamed stack stays an ordinary item that happens to glint.
        String[] lore = display.lore();
        if (!display.name().isEmpty() || lore.length > 0) {
            b.writeByte(TAG_COMPOUND);
            writeString(b, "display");

            if (!display.name().isEmpty()) {
                b.writeByte(TAG_STRING);
                writeString(b, "Name");
                writeString(b, display.name());
            }
            if (lore.length > 0) {
                b.writeByte(TAG_LIST);
                writeString(b, "Lore");
                b.writeByte(TAG_STRING);   // the list's element type
                b.writeIntLE(lore.length); // little-endian NBT: an LE int length
                for (String line : lore) {
                    writeString(b, line == null ? "" : line);
                }
            }
            b.writeByte(TAG_END);          // closes "display"
        }

        b.writeByte(TAG_END);            // closes the root
    }

    /**
     * The {@code ench} list — a compound per enchantment of {@code short id} / {@code short lvl}, at the
     * root beside {@code display}, little-endian like everything else here. Ids are <b>Bedrock's</b>,
     * which are not Java's: see {@link com.jedrock.network.EnchantmentIds}.
     */
    private static void writeEnchantments(ByteBuf b, com.jedrock.api.item.Enchantments enchantments,
                                          boolean era014) {
        if (!ENCHANT_NBT || enchantments == null || enchantments.isEmpty()) {
            return;
        }
        java.util.List<java.util.Map.Entry<com.jedrock.api.item.Enchantment, Integer>> entries =
                new java.util.ArrayList<>(enchantments.size());
        for (var entry : enchantments.asMap().entrySet()) {
            if (era014 && !com.jedrock.network.EnchantmentIds.supportedBy014(entry.getKey())) {
                continue;   // an id this era never had; it crashes rather than shrugs
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            return;
        }
        b.writeByte(TAG_LIST);
        writeString(b, "ench");
        b.writeByte(TAG_COMPOUND);       // the list's element type
        b.writeIntLE(entries.size());    // little-endian NBT: an LE int length
        for (var entry : entries) {
            b.writeByte(TAG_SHORT);
            writeString(b, "id");
            b.writeShortLE(com.jedrock.network.EnchantmentIds.bedrockId(entry.getKey()));
            b.writeByte(TAG_SHORT);
            writeString(b, "lvl");
            b.writeShortLE(entry.getValue());
            b.writeByte(TAG_END);        // closes this entry's compound
        }
    }

    private static void writeString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        b.writeShortLE(bytes.length);    // little-endian NBT: an LE-short length
        b.writeBytes(bytes);
    }
}
