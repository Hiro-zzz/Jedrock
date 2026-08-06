package com.jedrock.network.pe.v014;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound MCPE 0.14 (protocol 45) packet ids and body encoders, verified against PocketMine-MP at
 * {@code CURRENT_PROTOCOL = 45} (commit e11b76318). Each encoder writes {@code [packetId][big-endian
 * body]} into the buffer; the caller adds the {@code 0x8e} game wrapper (or a 0x92 batch). All fields
 * are big-endian, which is Netty {@link ByteBuf}'s default — see {@link Mcpe014Codec}.
 */
public final class Mcpe014Packets {

    private Mcpe014Packets() {}

    /** One-byte wrapper prepended to every game packet, both directions (RakLibInterface #blameshoghi). */
    public static final int WRAPPER = 0x8e;

    // Packet ids (high-id scheme).
    public static final int ID_LOGIN = 0x8f;
    public static final int ID_PLAY_STATUS = 0x90;
    public static final int ID_DISCONNECT = 0x91;
    public static final int ID_BATCH = 0x92;
    public static final int ID_TEXT = 0x93;
    public static final int ID_SET_TIME = 0x94;
    public static final int ID_START_GAME = 0x95;
    public static final int ID_ADD_PLAYER = 0x96;
    public static final int ID_REMOVE_PLAYER = 0x97;
    public static final int ID_ADD_ENTITY = 0x98;  // outbound: spawn a non-player entity (a puppet)
    public static final int ID_REMOVE_ENTITY = 0x99;
    public static final int ID_MOVE_ENTITY = 0x9c;
    public static final int ID_MOVE_PLAYER = 0x9d;
    public static final int ID_REMOVE_BLOCK = 0x9e;
    public static final int ID_UPDATE_BLOCK = 0x9f;
    public static final int ID_LEVEL_EVENT = 0xa2;  // outbound: world effects — 1000-series sounds, 0x4000|particle
    public static final int ID_MOB_EQUIPMENT = 0xa7; // both ways: the item in an entity's hand (held-item sync)
    public static final int ID_MOB_ARMOR_EQUIPMENT = 0xa8; // outbound: the four worn armor pieces, one packet
    public static final int ID_ADD_ITEM_ENTITY = 0x9a;     // outbound: a dropped-item entity, carrying its item
    public static final int WINDOW_ID_ARMOR = 0x78;        // the wearer's own armor slots (SPECIAL_ARMOR)
    public static final int ID_ENTITY_EVENT = 0xa4; // outbound: one-shot entity event (hurt animation etc.)
    public static final int ID_MOB_EFFECT = 0xa5;  // outbound: add / modify / remove a status effect
    /** MobEffect events — the same three at both PE eras. */
    public static final int EFFECT_EVENT_ADD = 1;
    public static final int EFFECT_EVENT_MODIFY = 2;
    public static final int EFFECT_EVENT_REMOVE = 3;
    public static final int ID_INTERACT = 0xa9;    // inbound: attack / interact with an entity
    public static final int ID_USE_ITEM = 0xaa;
    public static final int ID_PLAYER_ACTION = 0xab;
    public static final int ID_SET_ENTITY_DATA = 0xad;
    public static final int ID_SET_HEALTH = 0xb0;
    public static final int ID_SET_SPAWN_POSITION = 0xb1;
    public static final int ID_ANIMATE = 0xb2;
    public static final int ID_RESPAWN = 0xb3;
    public static final int ID_CONTAINER_OPEN = 0xb5;      // open a container GUI (chest)
    public static final int ID_CONTAINER_CLOSE = 0xb6;     // close a container (both directions)
    public static final int ID_CONTAINER_SET_SLOT = 0xb7;  // inbound: a client-driven slot move
    public static final int ID_CONTAINER_SET_CONTENT = 0xb9;
    public static final int ID_FULL_CHUNK_DATA = 0xbf;
    public static final int ID_SET_DIFFICULTY = 0xc0;
    public static final int ID_PLAYER_LIST = 0xc3;
    public static final int ID_REQUEST_CHUNK_RADIUS = 0xc8;
    public static final int ID_CHUNK_RADIUS_UPDATE = 0xc9;

    // PlayerList types.
    public static final int PLAYER_LIST_ADD = 0;
    public static final int PLAYER_LIST_REMOVE = 1;
    // Entity metadata (0.14): DATA_FLAGS is a BYTE at index 0 (not a LONG like 1.1.5); terminator 0x7f.
    // 0.14 copies the PC 1.8 layout, so the nametag sits at index 2 and its visibility at 3 — where 1.1.5
    // has the nametag at 4 and folds visibility into flag bits. Writing 1.1.5's indices here does nothing.
    public static final int DATA_FLAGS_INDEX = 0;
    public static final int DATA_NAMETAG_INDEX = 2;       // string: the floating nametag
    public static final int DATA_SHOW_NAMETAG_INDEX = 3;  // byte: 1 = always show it
    public static final int DATA_NO_AI_INDEX = 15;        // byte: 1 = immobile
    /** Int: a falling block's {@code id | (meta << 8)}. PMMP calls it DATA_BLOCK_INFO — index 20 at 0.14,
     *  where protocol 113 moved the same value to DATA_VARIANT (index 2). */
    public static final int DATA_BLOCK_INFO_INDEX = 20;
    public static final int DATA_TYPE_INT = 2;
    public static final int DATA_TYPE_BYTE = 0;
    public static final int DATA_TYPE_STRING = 4;
    public static final int META_END = 0x7f;
    public static final int FLAG_ON_FIRE = 1 << 0;  // 0x01
    public static final int FLAG_SNEAKING = 1 << 1; // 0x02
    public static final int FLAG_SPRINTING = 1 << 3; // 0x08
    public static final int FLAG_ACTION = 1 << 4;   // 0x10 — using an item
    public static final int FLAG_INVISIBLE = 1 << 5; // 0x20

    /**
     * The MCPE entity id of a dropped-item entity (64 at both PE eras) — a hologram line hangs its name on
     * one with no item attached, so only the text renders. PocketMine's own floating-text hack.
     */
    public static final int ITEM_ENTITY_TYPE_ID = 64;

    /** PocketMine's own offset for floating text, so the name lands on the requested y. */
    public static final double TEXT_LINE_Y_OFFSET = -0.75;

    // TextPacket types.
    public static final int TEXT_TYPE_RAW = 0;
    public static final int TEXT_TYPE_CHAT = 1;
    /** Two strings (source + message): the HUD line above the hotbar, where a held item's name shows. */
    public static final int TEXT_TYPE_POPUP = 3;
    public static final int TEXT_TYPE_TIP = 4;
    // MovePlayer modes.
    public static final int MOVE_MODE_NORMAL = 0;
    public static final int MOVE_MODE_RESET = 1;
    // InteractPacket action: a left-click = a melee attack.
    public static final int INTERACT_LEFT_CLICK = 2;
    // EntityEvent event: a living entity is hurt (damage flash + sound).
    public static final int ENTITY_EVENT_HURT = 2;
    // PlayerAction actions.
    public static final int ACTION_START_SPRINT = 9;
    public static final int ACTION_STOP_SPRINT = 10;
    public static final int ACTION_START_SNEAK = 11;
    public static final int ACTION_STOP_SNEAK = 12;
    // UpdateBlock flags (neighbours | network) — apply + re-render.
    public static final int UPDATE_BLOCK_FLAG_ALL = 0b0011;
    // Animate action: main-hand swing.
    public static final int ANIMATE_SWING = 1;
    /** MCPE positions are eye-level: entity/move Y = feet + this (player eye height). */
    public static final float EYE_HEIGHT = 1.62f;
    /** Block-face offsets (down, up, north, south, west, east). */
    public static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    public static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};

    // PlayStatus values.
    public static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    public static final int PLAY_STATUS_PLAYER_SPAWN = 3;

    // ContainerSetContent window ids (PocketMine protocol 45: SPECIAL_INVENTORY=0, SPECIAL_CREATIVE=0x79).
    public static final int WINDOW_ID_CREATIVE = 0x79;
    public static final int WINDOW_ID_PLAYER = 0;      // the player's own inventory window

    // FullChunkData order.
    public static final int ORDER_COLUMNS = 0;

    public static void playStatus(ByteBuf b, int status) {
        b.writeByte(ID_PLAY_STATUS);
        b.writeInt(status);
    }

    public static void startGame(ByteBuf b, int seed, int dimension, int generator, int gamemode,
                                 long eid, int spawnX, int spawnY, int spawnZ,
                                 float x, float y, float z) {
        b.writeByte(ID_START_GAME);
        b.writeInt(seed);
        b.writeByte(dimension);
        b.writeInt(generator);
        b.writeInt(gamemode);
        b.writeLong(eid);
        b.writeInt(spawnX);
        b.writeInt(spawnY);
        b.writeInt(spawnZ);
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
        b.writeByte(0);
    }

    public static void setTime(ByteBuf b, int time, boolean started) {
        b.writeByte(ID_SET_TIME);
        b.writeInt(time);
        b.writeByte(started ? 1 : 0);
    }

    public static void setSpawnPosition(ByteBuf b, int x, int y, int z) {
        b.writeByte(ID_SET_SPAWN_POSITION);
        b.writeInt(x);
        b.writeInt(y);
        b.writeInt(z);
    }

    public static void setHealth(ByteBuf b, int health) {
        b.writeByte(ID_SET_HEALTH);
        b.writeInt(health);
    }

    public static void setDifficulty(ByteBuf b, int difficulty) {
        b.writeByte(ID_SET_DIFFICULTY);
        b.writeInt(difficulty);
    }

    public static void respawn(ByteBuf b, float x, float y, float z) {
        b.writeByte(ID_RESPAWN);
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
    }

    public static void chunkRadiusUpdate(ByteBuf b, int radius) {
        b.writeByte(ID_CHUNK_RADIUS_UPDATE);
        b.writeInt(radius);
    }

    /** ContainerOpen (0.14): {@code byte windowid, byte type, short slots, int x, int y, int z} (all BE). */
    public static void containerOpen(ByteBuf b, int windowId, int type, int slots, int x, int y, int z) {
        b.writeByte(ID_CONTAINER_OPEN);
        b.writeByte(windowId);
        b.writeByte(type);
        b.writeShort(slots);
        b.writeInt(x);
        b.writeInt(y);
        b.writeInt(z);
    }

    /** EntityEvent (0.14): {@code long eid} (BE) + {@code byte event}. No trailing data at protocol 45. */
    /**
     * MobEffect (0xa5), verbatim from PMMP at {@code CURRENT_PROTOCOL = 45}: {@code long eid},
     * {@code byte eventId}, {@code byte effectId}, {@code byte amplifier}, {@code byte particles},
     * {@code int duration} — all big-endian and fixed-width, and every field a <b>byte</b> where 1.1.5
     * uses a varint. Events are the same three: 1 add, 2 modify, 3 remove.
     *
     * <p>The caller is expected to have checked {@link Pe014Effects#supports} first: this client has no
     * placeholder for an effect it doesn't know, and it is the one that crashes rather than shrugs.
     */
    public static void mobEffect(ByteBuf b, long eid, int event, int effectId, int amplifier,
                                 boolean particles, int durationTicks) {
        b.writeByte(ID_MOB_EFFECT);
        b.writeLong(eid);
        b.writeByte(event);
        b.writeByte(effectId);
        b.writeByte(amplifier);
        b.writeByte(particles ? 1 : 0);
        b.writeInt(durationTicks);
    }

    public static void entityEvent(ByteBuf b, long eid, int event) {
        b.writeByte(ID_ENTITY_EVENT);
        b.writeLong(eid);
        b.writeByte(event);
    }

    /**
     * LevelEvent (0xa2) — a world effect at a position: a 1000-series sound id, or
     * {@code 0x4000 | particle type} for one particle. Layout, verbatim from the 0.14-era PMMP
     * {@code LevelEventPacket}: event id (short), position (3 × float), data (int — pitch×1000 for
     * sounds, 0 for particles). All big-endian, like the rest of the 0.14 wire.
     */
    public static void levelEvent(ByteBuf b, int evid, double x, double y, double z, int data) {
        b.writeByte(ID_LEVEL_EVENT);
        b.writeShort(evid);
        b.writeFloat((float) x);
        b.writeFloat((float) y);
        b.writeFloat((float) z);
        b.writeInt(data);
    }

    /** FullChunkData header; append the {@code data} blob after this. */
    public static void fullChunkDataHeader(ByteBuf b, int chunkX, int chunkZ, int dataLen) {
        b.writeByte(ID_FULL_CHUNK_DATA);
        b.writeInt(chunkX);
        b.writeInt(chunkZ);
        b.writeByte(ORDER_COLUMNS);
        b.writeInt(dataLen);
    }

    /** A raw (system) chat line. */
    public static void text(ByteBuf b, String message) {
        b.writeByte(ID_TEXT);
        b.writeByte(TEXT_TYPE_RAW);
        Mcpe014Codec.writeString(b, message);
    }

    /**
     * A popup: the HUD line above the hotbar (the held item's name field, displaced upward). Layout,
     * verbatim from PMMP {@code TextPacket} at protocol 45 — {@code type} (byte) then, for
     * {@code TYPE_POPUP}, {@code source} (the popup line) and {@code message} (the text under it), both
     * this era's big-endian short-length strings. Structurally identical to 113; only the strings differ.
     */
    public static void popup(ByteBuf b, String source, String message) {
        b.writeByte(ID_TEXT);
        b.writeByte(TEXT_TYPE_POPUP);
        Mcpe014Codec.writeString(b, source == null ? "" : source);
        Mcpe014Codec.writeString(b, message == null ? "" : message);
    }

    /** Spawn another player's avatar. {@code y} is FEET; the metadata is empty (0x7f terminator). */
    public static void addPlayer(ByteBuf b, java.util.UUID uuid, String name, long eid,
                                 float x, float y, float z, float yaw, float pitch) {
        b.writeByte(ID_ADD_PLAYER);
        Mcpe014Codec.writeUuid(b, uuid);
        Mcpe014Codec.writeString(b, name);
        b.writeLong(eid);
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
        b.writeFloat(0f);          // speed x
        b.writeFloat(0f);          // speed y
        b.writeFloat(0f);          // speed z
        b.writeFloat(yaw);
        b.writeFloat(yaw);         // head yaw
        b.writeFloat(pitch);
        b.writeShort(0);           // held item: air (empty slot)
        b.writeByte(0x7f);         // empty entity metadata
    }

    /**
     * Spawn a non-player entity (a puppet) with no metadata — a bare visual. {@code y} is FEET.
     */
    public static void addEntity(ByteBuf b, long eid, int type,
                                 float x, float y, float z, float yaw, float pitch) {
        addEntity(b, eid, type, x, y, z, yaw, pitch, meta -> {});
    }

    /**
     * Spawn a non-player entity, {@code metadata} writing the metadata entries (the {@code 0x7f} terminator
     * is added here). {@code y} is FEET.
     *
     * <p>Field order is verbatim from PocketMine-MP {@code AddEntityPacket::encode} at protocol 45: the
     * metadata block comes <em>before</em> the entity-links short, not after.
     */
    public static void addEntity(ByteBuf b, long eid, int type,
                                 float x, float y, float z, float yaw, float pitch,
                                 java.util.function.Consumer<ByteBuf> metadata) {
        b.writeByte(ID_ADD_ENTITY);
        b.writeLong(eid);
        b.writeInt(type);          // entity type (MCPE id)
        b.writeFloat(x);
        b.writeFloat(y);           // feet
        b.writeFloat(z);
        b.writeFloat(0f);          // speed x
        b.writeFloat(0f);          // speed y
        b.writeFloat(0f);          // speed z
        b.writeFloat(yaw);
        b.writeFloat(pitch);
        metadata.accept(b);
        b.writeByte(META_END);     // terminates the metadata block
        b.writeShort(0);           // entity links: none
    }

    /**
     * AddItemEntity (0x9a) — a dropped-item entity carrying its item, the decoration primitive. Layout,
     * verbatim from the 0.14-era PMMP {@code AddItemEntityPacket}: eid (BE long), the item Slot, then the
     * position and speed as three big-endian floats each. Unlike 113's version it has <b>no metadata
     * field</b>, so immobility has to follow in its own packet — see {@link #setEntityNoAi}.
     */
    public static void addItemEntity(ByteBuf b, long eid, double x, double y, double z, int state) {
        b.writeByte(ID_ADD_ITEM_ENTITY);
        b.writeLong(eid);
        writeSlot(b, state, state == 0 ? 0 : 1);
        b.writeFloat((float) x); b.writeFloat((float) y); b.writeFloat((float) z);
        b.writeFloat(0f); b.writeFloat(0f); b.writeFloat(0f); // speed: none, it's a prop
    }

    /**
     * A falling-block prop: an ordinary AddEntity of the FallingSand type, immobile, with the block it
     * renders in DATA_BLOCK_INFO (index 20 at 0.14) as {@code id | (meta << 8)}.
     */
    public static void addFallingBlock(ByteBuf b, long eid, double x, double y, double z, int blockInfo) {
        addEntity(b, eid, com.jedrock.network.EntityTypeIds.BEDROCK_FALLING_BLOCK,
                (float) x, (float) y, (float) z, 0f, 0f,
                meta -> {
                    writeMetaByte(meta, DATA_NO_AI_INDEX, 1);
                    meta.writeByte((DATA_TYPE_INT << 5) | DATA_BLOCK_INFO_INDEX);
                    meta.writeInt(blockInfo);
                });
    }

    /** Pin an entity in place (SetEntityData with NO_AI) — what keeps a prop from drifting at 0.14. */
    public static void setEntityNoAi(ByteBuf b, long eid) {
        b.writeByte(ID_SET_ENTITY_DATA);
        b.writeLong(eid);
        writeMetaByte(b, DATA_NO_AI_INDEX, 1);
        b.writeByte(META_END);
    }

    /** One line of a hologram: an invisible, immobile item entity whose nametag is the floating text. */
    public static void addTextLine(ByteBuf b, long eid, float x, float y, float z, String text) {
        addEntity(b, eid, ITEM_ENTITY_TYPE_ID, x, (float) (y + TEXT_LINE_Y_OFFSET), z, 0f, 0f,
                meta -> {
                    writeMetaByte(meta, DATA_FLAGS_INDEX, FLAG_INVISIBLE);
                    writeNameTagEntries(meta, text);
                    writeMetaByte(meta, DATA_NO_AI_INDEX, 1);
                });
    }

    /** The nametag string plus its visibility byte — the pair 0.14 needs to float a name. */
    public static void writeNameTagEntries(ByteBuf b, String text) {
        boolean shown = text != null && !text.isEmpty();
        b.writeByte((DATA_TYPE_STRING << 5) | DATA_NAMETAG_INDEX);
        writeMetaString(b, shown ? text : "");
        writeMetaByte(b, DATA_SHOW_NAMETAG_INDEX, shown ? 1 : 0);
    }

    /** One byte-typed metadata entry: the key header packs type and index into a single byte. */
    public static void writeMetaByte(ByteBuf b, int index, int value) {
        b.writeByte((DATA_TYPE_BYTE << 5) | index);
        b.writeByte(value);
    }

    /**
     * A string <em>inside</em> a metadata block: a LITTLE-endian short length + UTF-8. Every other string
     * in protocol 45 is big-endian ({@link Mcpe014Codec#writeString}); PocketMine's {@code writeMetadata}
     * reaches for {@code writeLShort} here alone, so this one must not go through the codec.
     */
    private static void writeMetaString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.writeShortLE(bytes.length);
        b.writeBytes(bytes);
    }

    /** Move an entity (avatar). {@code y} must be EYE-level (feet + {@link #EYE_HEIGHT}). */
    public static void moveEntity(ByteBuf b, long eid, float x, float y, float z, float yaw, float pitch) {
        moveEntity(b, eid, x, y, z, yaw, pitch, yaw);
    }

    /**
     * As above, with the head aimed separately from the body. The head-yaw float has been in this packet
     * since long before 0.14 — it was simply being handed the body yaw twice.
     */
    public static void moveEntity(ByteBuf b, long eid, float x, float y, float z,
                                  float bodyYaw, float pitch, float headYaw) {
        b.writeByte(ID_MOVE_ENTITY);
        b.writeInt(1);             // entity count
        b.writeLong(eid);
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
        b.writeFloat(bodyYaw);
        b.writeFloat(headYaw);     // head yaw
        b.writeFloat(pitch);
    }

    /** Reposition this client's own player (self eid = 0). {@code y} is EYE-level. */
    public static void movePlayerSelf(ByteBuf b, float x, float y, float z, float yaw, float pitch, int mode) {
        b.writeByte(ID_MOVE_PLAYER);
        b.writeLong(0L);           // self entity id (StartGame assigned 0)
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
        b.writeFloat(yaw);
        b.writeFloat(yaw);         // body yaw
        b.writeFloat(pitch);
        b.writeByte(mode);
        b.writeByte(1);            // on ground
    }

    public static void removeEntity(ByteBuf b, long eid) {
        b.writeByte(ID_REMOVE_ENTITY);
        b.writeLong(eid);
    }

    public static void animate(ByteBuf b, int action, long eid) {
        b.writeByte(ID_ANIMATE);
        b.writeByte(action);
        b.writeLong(eid);
    }

    /**
     * Add one entry to the pause-menu player list. The skin must be a valid RGBA texture — the 0.14
     * client crashes on an empty one — so callers pass a real or {@link Mcpe014Skin synthetic} skin.
     */
    public static void playerListAdd(ByteBuf b, java.util.UUID uuid, long eid, String name,
                                     String skinName, byte[] skinData) {
        b.writeByte(ID_PLAYER_LIST);
        b.writeByte(PLAYER_LIST_ADD);
        b.writeInt(1);                 // entry count
        Mcpe014Codec.writeUuid(b, uuid);
        b.writeLong(eid);
        Mcpe014Codec.writeString(b, name);
        Mcpe014Codec.writeString(b, skinName);
        b.writeShort(skinData.length); // skin data: 2-byte BE length + RGBA bytes
        b.writeBytes(skinData);
    }

    /** Remove one entry from the player list. */
    public static void playerListRemove(ByteBuf b, java.util.UUID uuid) {
        b.writeByte(ID_PLAYER_LIST);
        b.writeByte(PLAYER_LIST_REMOVE);
        b.writeInt(1);
        Mcpe014Codec.writeUuid(b, uuid);
    }

    /**
     * Set an entity's pose via SetEntityData: the DATA_FLAGS byte (crouch / sprint / item-use). In
     * 0.14 the nametag is a separate metadata index, so writing only the flags never clears the name.
     */
    public static void setEntityDataFlags(ByteBuf b, long eid,
                                          boolean sneaking, boolean sprinting, boolean usingItem) {
        setEntityFlags(b, eid, (sneaking ? FLAG_SNEAKING : 0) | (sprinting ? FLAG_SPRINTING : 0)
                | (usingItem ? FLAG_ACTION : 0));
    }

    /** Set an entity's whole DATA_FLAGS byte (every flag lives in this one field, so it is written whole). */
    public static void setEntityFlags(ByteBuf b, long eid, int flags) {
        b.writeByte(ID_SET_ENTITY_DATA);
        b.writeLong(eid);
        writeMetaByte(b, DATA_FLAGS_INDEX, flags);
        b.writeByte(META_END);
    }

    /** Set an entity's floating nametag; the flags field is a different index, so it stays untouched. */
    public static void setEntityNameTag(ByteBuf b, long eid, String text) {
        b.writeByte(ID_SET_ENTITY_DATA);
        b.writeLong(eid);
        writeNameTagEntries(b, text);
        b.writeByte(META_END);
    }

    /**
     * Fill a container window with items — used for the creative menu (windowId {@link #WINDOW_ID_CREATIVE}).
     * Protocol-45 body: {@code byte windowId, short slotCount, slots..., short 0} (the trailing short is
     * the hotbar-link count, always 0 for a non-inventory window). Each state is a canonical
     * {@code (id << 4) | meta}, so per-meta variants (wool colours, wood types, …) show as distinct items.
     */
    public static void containerSetContent(ByteBuf b, int windowId, int[] states, int count) {
        b.writeByte(ID_CONTAINER_SET_CONTENT);
        b.writeByte(windowId);
        b.writeShort(states.length);
        for (int state : states) {
            writeSlot(b, state, count);
        }
        b.writeShort(0); // hotbar-link count (none)
    }

    /**
     * ContainerSetContent (0xb9) for the player's own inventory (window 0), PMMP-shaped
     * ({@code PlayerInventory::sendContents} in the 0.14 tree): window id, short count = the 36
     * storage slots, the slots, then the 9-entry hotbar-link table — short count 9 followed by int
     * links {@code i + 9} (the identity map PMMP sends the holder). Without the links the client
     * fills storage but leaves its hotbar HUD empty.
     */
    public static void playerInventory(ByteBuf b, int[] states, int[] counts) {
        playerInventory(b, states, counts, null);
    }

    /** As above, with a per-slot custom-item display ({@code null} entries = ordinary items). */
    public static void playerInventory(ByteBuf b, int[] states, int[] counts, com.jedrock.api.item.ItemDisplay[] display) {
        b.writeByte(ID_CONTAINER_SET_CONTENT);
        b.writeByte(WINDOW_ID_PLAYER);
        b.writeShort(states.length);
        for (int i = 0; i < states.length; i++) {
            writeSlot(b, states[i], counts[i], display == null || i >= display.length ? null : display[i]);
        }
        b.writeShort(9);
        for (int i = 0; i < 9; i++) {
            b.writeInt(i + 9);
        }
    }

    /**
     * MobEquipment (0xa7) — the item in an entity's hand. Layout, verbatim from the 0.14-era PMMP
     * {@code MobEquipmentPacket}: eid (BE long), the item Slot, slot (byte), selectedSlot (byte).
     * Note the 113 packet adds a trailing windowId byte; 0.14 has only the two slot bytes. The slot
     * bytes matter to the holder's own inventory; a viewer just renders the item.
     */
    public static void mobEquipment(ByteBuf b, long eid, int state) {
        b.writeByte(ID_MOB_EQUIPMENT);
        b.writeLong(eid);
        writeSlot(b, state, state == 0 ? 0 : 1);
        b.writeByte(0);
        b.writeByte(0);
    }

    /**
     * The wearer's own armor: a ContainerSetContent for the armor window (0x78). PMMP's
     * {@code sendArmorContents} sends the holder this rather than the MobArmorEquipment other players
     * get — without it a 0.14 player sees everyone's armor but their own. Body: window id, slot count,
     * the four slots, then the trailing hotbar-link count of 0 (links exist only for window 0).
     */
    public static void ownArmor(ByteBuf b, int helmet, int chestplate, int leggings, int boots) {
        b.writeByte(ID_CONTAINER_SET_CONTENT);
        b.writeByte(WINDOW_ID_ARMOR);
        b.writeShort(4);
        for (int state : new int[]{helmet, chestplate, leggings, boots}) {
            writeSlot(b, state, state == 0 ? 0 : 1);
        }
        b.writeShort(0);
    }

    /**
     * MobArmorEquipment (0xa8) — the four worn pieces in one packet, head-to-feet. Layout, verbatim
     * from the 0.14-era PMMP {@code MobArmorEquipmentPacket}: eid (BE long) then four Slots. Same
     * shape as 113's 0x20 apart from the era's big-endian eid.
     */
    public static void mobArmorEquipment(ByteBuf b, long eid,
                                         int helmet, int chestplate, int leggings, int boots) {
        b.writeByte(ID_MOB_ARMOR_EQUIPMENT);
        b.writeLong(eid);
        for (int state : new int[]{helmet, chestplate, leggings, boots}) {
            writeSlot(b, state, state == 0 ? 0 : 1);
        }
    }

    /**
     * ContainerSetSlot (0xb7) outbound — one slot update, which refreshes the hotbar HUD live.
     * PMMP-shaped: window id (byte), slot (short), hotbarSlot (short, 0 — PMMP's default), item.
     */
    public static void containerSetSlot(ByteBuf b, int windowId, int slot, int state, int count) {
        containerSetSlot(b, windowId, slot, state, count, null);
    }

    /** As above, carrying a custom item's name and lore. */
    public static void containerSetSlot(ByteBuf b, int windowId, int slot, int state, int count,
                                        com.jedrock.api.item.ItemDisplay display) {
        b.writeByte(ID_CONTAINER_SET_SLOT);
        b.writeByte(windowId);
        b.writeShort(slot);
        b.writeShort(0);
        writeSlot(b, state, count, display);
    }

    /**
     * One 0.14 network item slot from a canonical {@code (id << 4) | meta} state:
     * {@code short id} (0 = air, nothing more), else {@code byte count, short meta, short nbtLen(0)}.
     */
    public static void writeSlot(ByteBuf b, int state, int count) {
        writeSlot(b, state, count, null);
    }

    /** As above, with a custom item's name and lore in the NBT field ({@code null} = an ordinary item). */
    public static void writeSlot(ByteBuf b, int state, int count,
                                 com.jedrock.api.item.ItemDisplay display) {
        int id = state >> 4;
        if (id <= 0) {
            b.writeShort(0); // air
            return;
        }
        b.writeShort(id);
        b.writeByte(count);
        b.writeShort(state & 0x0F);                  // meta / damage
        // Shared with 1.1.5: an item's NBT is little-endian on BOTH eras (see McpeItemNbt). The era flag
        // is the enchantment gate — this client knows ids up to 24 and crashes on what it doesn't.
        com.jedrock.network.pe.McpeItemNbt.writeSlotNbt(b, display, true);
    }

    /** Single-block change. {@code id}/{@code meta} are the split canonical state. */
    public static void updateBlock(ByteBuf b, int x, int y, int z, int id, int meta) {
        b.writeByte(ID_UPDATE_BLOCK);
        b.writeInt(1);             // record count
        b.writeInt(x);
        b.writeInt(z);
        b.writeByte(y);
        b.writeByte(id);
        b.writeByte((UPDATE_BLOCK_FLAG_ALL << 4) | (meta & 0x0F));
    }
}
