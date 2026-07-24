package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.function.Consumer;

/**
 * Clientbound Entity Metadata (0x3C) for JE 1.12.2 — a partial update of an entity's metadata dictionary:
 * a run of {@code index (u8), type (VarInt), value} entries ending with {@code 0xFF}. Only the entries
 * written are touched, so a pose update never disturbs a name tag. The exception is a <em>field</em>: the
 * flags byte holds every flag at once, so it is always written whole.
 *
 * <p>Type ids are 1.12.2's ({@code Byte 0, VarInt 1, Float 2, String 3, Chat 4, Slot 5, Boolean 6}) — not
 * 1.13+'s, which inserts OptChat at 5 and shifts the rest, and not 1.8's, which is a different encoding
 * entirely (see {@code Java1_8ProtocolHandler}).
 */
public final class ClientboundEntityMetadata implements ClientboundPacket {

    /** Shared entity flags (index 0). */
    public static final int FLAG_ON_FIRE = 0x01;
    public static final int FLAG_CROUCHED = 0x02;
    public static final int FLAG_SPRINTING = 0x08;
    public static final int FLAG_INVISIBLE = 0x20;
    /** Hand-states (index 6): 0x01 = hand active (using an item), 0x02 = which hand. */
    public static final int HAND_ACTIVE = 0x01;

    /** ArmorStand flags (index 11) — a hologram's line hangs on a marker stand. */
    static final int ARMOR_STAND_SMALL = 0x01;
    static final int ARMOR_STAND_NO_BASE_PLATE = 0x08;
    static final int ARMOR_STAND_MARKER = 0x10;

    private static final int INDEX_FLAGS = 0;
    private static final int INDEX_CUSTOM_NAME = 2;
    private static final int INDEX_CUSTOM_NAME_VISIBLE = 3;
    private static final int INDEX_NO_GRAVITY = 5;
    private static final int INDEX_HAND_STATES = 6;
    /** An item-stack entity's item — the same index the hand-states field uses on a living entity. */
    private static final int INDEX_ITEM = 6;
    private static final int INDEX_ARMOR_STAND_FLAGS = 11;

    private static final int TYPE_BYTE = 0;
    private static final int TYPE_STRING = 3;
    private static final int TYPE_SLOT = 5;
    private static final int TYPE_BOOLEAN = 6;
    private static final int METADATA_END = 0xFF;

    private final int entityId;
    private final Consumer<ByteBuf> entries;

    private ClientboundEntityMetadata(int entityId, Consumer<ByteBuf> entries) {
        this.entityId = entityId;
        this.entries = entries;
    }

    /** Metadata for a full pose — crouch/sprint flags and the item-use hand state, in one packet. */
    public static ClientboundEntityMetadata pose(int entityId, boolean sneaking, boolean sprinting,
                                                 boolean usingItem) {
        int flags = (sneaking ? FLAG_CROUCHED : 0) | (sprinting ? FLAG_SPRINTING : 0);
        int handStates = usingItem ? HAND_ACTIVE : 0;
        return new ClientboundEntityMetadata(entityId, b -> {
            writeByteEntry(b, INDEX_FLAGS, flags);
            writeByteEntry(b, INDEX_HAND_STATES, handStates);
        });
    }

    /** A puppet's whole flags byte (already translated to this edition's bits by {@code EntityFlagIds}). */
    public static ClientboundEntityMetadata flags(int entityId, int flags) {
        return new ClientboundEntityMetadata(entityId, b -> writeByteEntry(b, INDEX_FLAGS, flags));
    }

    /** A puppet's floating name; {@code null} / empty hides it. */
    public static ClientboundEntityMetadata nameTag(int entityId, String name) {
        return new ClientboundEntityMetadata(entityId, b -> writeNameTag(b, name));
    }

    /**
     * The item an item-stack entity renders. Index 6 at 1.12.2 — ViaVersion's table puts it at 5 in 1.9,
     * and 1.10 inserted no-gravity at 5, shifting every later subclass field by one. Also pins no-gravity
     * so a prop never drifts.
     */
    /**
     * Pin an entity against the client's own physics (no-gravity, index 5 — added in 1.10). Needed by
     * the few entity types a client animates itself, above all a falling block.
     */
    public static ClientboundEntityMetadata noGravity(int entityId) {
        return new ClientboundEntityMetadata(entityId, b -> writeBooleanEntry(b, INDEX_NO_GRAVITY, true));
    }

    public static ClientboundEntityMetadata item(int entityId, int state) {
        return new ClientboundEntityMetadata(entityId, b -> {
            writeKey(b, INDEX_ITEM, TYPE_SLOT);
            JeSlots.write(b, state, state == 0 ? 0 : 1);
            writeBooleanEntry(b, INDEX_NO_GRAVITY, true);
        });
    }

    /**
     * The metadata a hologram line's armor stand carries, written into a Spawn Mob payload: invisible, no
     * gravity, and a marker (no hitbox, so it can't be hit or block anything) whose only visible trace is
     * the name floating where the body would be.
     */
    static void writeTextLineEntries(ByteBuf buf, String text) {
        writeByteEntry(buf, INDEX_FLAGS, FLAG_INVISIBLE);
        writeNameTag(buf, text);
        writeBooleanEntry(buf, INDEX_NO_GRAVITY, true);
        writeByteEntry(buf, INDEX_ARMOR_STAND_FLAGS,
                ARMOR_STAND_MARKER | ARMOR_STAND_SMALL | ARMOR_STAND_NO_BASE_PLATE);
    }

    /** Custom name (a plain String at 1.12.2 — OptChat is 1.13+) plus its visibility. */
    private static void writeNameTag(ByteBuf buf, String name) {
        boolean shown = name != null && !name.isEmpty();
        writeKey(buf, INDEX_CUSTOM_NAME, TYPE_STRING);
        ByteBufUtils.writeString(buf, shown ? name : "");
        writeBooleanEntry(buf, INDEX_CUSTOM_NAME_VISIBLE, shown);
    }

    private static void writeByteEntry(ByteBuf buf, int index, int value) {
        writeKey(buf, index, TYPE_BYTE);
        buf.writeByte(value);
    }

    private static void writeBooleanEntry(ByteBuf buf, int index, boolean value) {
        writeKey(buf, index, TYPE_BOOLEAN);
        buf.writeBoolean(value);
    }

    private static void writeKey(ByteBuf buf, int index, int type) {
        buf.writeByte(index);
        ByteBufUtils.writeVarInt(buf, type);
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        entries.accept(buf);
        buf.writeByte(METADATA_END); // 0xFF terminates the metadata list
    }

    @Override
    public int getPacketId() {
        return 0x3C;
    }
}
