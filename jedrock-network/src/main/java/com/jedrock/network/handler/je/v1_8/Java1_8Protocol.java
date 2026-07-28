package com.jedrock.network.handler.je.v1_8;

/**
 * Wire constants for Java Edition 1.8 (protocol 47).
 *
 * <p>1.8 packet ids differ from 1.12.2 but the framing (VarInt length + VarInt id + payload) and the
 * world's {@code (id<<4)|meta} block model are the same, so only the ids and a handful of format
 * deltas change. These ids are the well-known 1.8 assignments; they are centralised here so a
 * cross-check against an authoritative source (minecraft-data pc/1.8) is a one-file diff.
 */
public final class Java1_8Protocol {

    private Java1_8Protocol() {}

    // ===== LOGIN =====
    public static final int SB_LOGIN_START = 0x00;
    public static final int CB_LOGIN_SUCCESS = 0x02; // uuid (string) + name (string) — same as 1.12.2

    // ===== PLAY (clientbound) =====
    public static final int CB_KEEP_ALIVE = 0x00;        // VarInt id (1.12.2 uses a Long)
    public static final int CB_JOIN_GAME = 0x01;
    public static final int CB_RESPAWN = 0x07;          // move the client to another world (Join Game's tail)
    public static final int CB_CHAT = 0x02;             // chat/system; position byte 2 = action bar
    public static final int CB_TITLE = 0x45;            // title/subtitle/times (actions 0/1/2, 3=hide, 4=reset)
    public static final int CB_NAMED_SOUND = 0x29;      // named sound effect (string name + pos*8 + volume + pitch byte)
    public static final int CB_WORLD_PARTICLES = 0x2a;  // particle burst (id + pos + offsets + speed + count)
    public static final int CB_ENTITY_EQUIPMENT = 0x04; // eid (varint) + slot (short, 0 = held) + item
    public static final int CB_CHANGE_GAME_STATE = 0x2B; // reason byte + float value (gamemode switch)
    public static final int CB_UPDATE_HEALTH = 0x06;     // float health + food varint + saturation float
    public static final int CB_SPAWN_POSITION = 0x05;
    public static final int CB_POSITION = 0x08;          // player position and look (no teleport id in 1.8)
    public static final int CB_HELD_ITEM = 0x09;
    public static final int CB_ANIMATION = 0x0B;
    public static final int CB_SPAWN_PLAYER = 0x0C;       // named entity spawn (fixed-point coords)
    public static final int CB_SPAWN_MOB = 0x0F;          // non-player entity spawn (byte type, fixed-point)
    public static final int CB_ENTITY_DESTROY = 0x13;
    public static final int CB_ENTITY_TELEPORT = 0x18;   // fixed-point coords
    public static final int CB_ENTITY_HEAD_ROTATION = 0x19;
    public static final int CB_ENTITY_STATUS = 0x1A;     // int entityId + byte status (2 = hurt)
    public static final int CB_ENTITY_METADATA = 0x1C;
    public static final int CB_OPEN_WINDOW = 0x2D;   // open a container GUI (chest)
    public static final int CB_WINDOW_ITEMS = 0x30; // replace a window's contents (player inventory)
    public static final int CB_SET_SLOT = 0x2F;      // update one window slot
    public static final int CB_CONFIRM_TRANSACTION = 0x32; // ack a click (we always accept)
    public static final int CB_CHUNK_DATA = 0x21;
    public static final int CB_BLOCK_CHANGE = 0x23;
    public static final int CB_PLAYER_LIST_ITEM = 0x38;
    public static final int CB_PLAYER_ABILITIES = 0x39;
    public static final int CB_SERVER_DIFFICULTY = 0x41;

    // ===== PLAY (serverbound) =====
    public static final int SB_KEEP_ALIVE = 0x00;
    public static final int SB_CHAT = 0x01;
    public static final int SB_USE_ENTITY = 0x02;   // attack / interact with another entity
    public static final int SB_POSITION = 0x04;
    public static final int SB_LOOK = 0x05;
    public static final int SB_POSITION_LOOK = 0x06;
    public static final int SB_BLOCK_DIG = 0x07;
    public static final int SB_BLOCK_PLACE = 0x08;
    public static final int SB_HELD_ITEM = 0x09;
    public static final int SB_ARM_ANIMATION = 0x0A;
    public static final int SB_ENTITY_ACTION = 0x0B;
    public static final int SB_CLOSE_WINDOW = 0x0D;
    public static final int SB_CLICK_WINDOW = 0x0E;         // mode is a BYTE in 1.8 (VarInt only from 1.9)
    public static final int SB_CONFIRM_TRANSACTION = 0x0F;  // client's ack — ignored
    public static final int SB_CREATIVE_ACTION = 0x10;      // creative set-slot (slot short + item)
    public static final int SB_TAB_COMPLETE = 0x14;         // serverbound Tab-Complete request (text + flags)
    public static final int CB_TAB_COMPLETE = 0x3A;         // clientbound Tab-Complete: VarInt count + strings
    public static final int CB_SCOREBOARD_OBJECTIVE = 0x3B; // create/update/remove a scoreboard objective
    public static final int CB_UPDATE_SCORE = 0x3C;         // set/remove one score entry
    public static final int CB_DISPLAY_OBJECTIVE = 0x3D;    // bind an objective to a display slot (1 = sidebar)

    // ===== 1.8 entity metadata (old format): header = (type << 5) | index, list ends with 0x7F =====
    // Type ids are 1.8's own (byte 0, short 1, int 2, float 3, string 4, …) — 1.12.2 renumbered them.
    public static final int META_TYPE_BYTE = 0;
    public static final int META_TYPE_INT = 2;
    public static final int META_TYPE_STRING = 4;
    public static final int META_TYPE_FLOAT = 3;
    public static final int META_INDEX_HEALTH = 6;              // float — drives a wither's boss-bar fill
    /** Wither-only: the invulnerability countdown. Kept high so the client's copy stays out of its
     *  spawn sequence — the value ViaRewind uses for exactly this trick. */
    public static final int META_INDEX_WITHER_INVUL = 20;       // int
    public static final int META_INDEX_FLAGS = 0;   // shared entity flags byte
    public static final int META_INDEX_CUSTOM_NAME = 2;          // string
    public static final int META_INDEX_CUSTOM_NAME_VISIBLE = 3;  // byte (1.8 has no boolean type)
    /** ArmorStand flags — index 10 in 1.8, where 1.12.2 puts them at 11. */
    public static final int META_INDEX_ARMOR_STAND_FLAGS = 10;
    public static final int META_END = 0x7F;
    public static final int FLAG_ON_FIRE = 0x01;
    public static final int FLAG_CROUCHED = 0x02;
    public static final int FLAG_SPRINTING = 0x08;
    public static final int FLAG_USING_ITEM = 0x10;  // eating / drinking / blocking / drawing bow
    public static final int FLAG_INVISIBLE = 0x20;

    /** ArmorStand flag bits (index 10) — a hologram line hangs on a small, marker, plateless stand. */
    public static final int ARMOR_STAND_SMALL = 0x01;
    public static final int ARMOR_STAND_NO_BASE_PLATE = 0x08;
    public static final int ARMOR_STAND_MARKER = 0x10;

    /** The classic mob id of an armor stand — the body a hologram line's text hangs on. */
    public static final int ARMOR_STAND_TYPE_ID = 30;

    /**
     * Where to put a hologram line's armor stand so its name lands on the requested y (see the 1.12.2
     * counterpart). Cosmetic and approximate — worth a nudge against a live client.
     */
    public static final double ARMOR_STAND_NAME_OFFSET = -0.5;

    /** Fixed-point factor for 1.8 absolute entity coordinates (block = value / 32). */
    public static final int FIXED_POINT = 32;

    /** Animation id for a main-hand swing. */
    public static final int ANIMATION_SWING = 0;

    /** Plains biome id used to fill the chunk's biome map. */
    public static final int PLAINS_BIOME = 1;

    /** Block-face offsets (down, up, north, south, west, east) for a 1.8 placement. */
    public static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    public static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};
}
