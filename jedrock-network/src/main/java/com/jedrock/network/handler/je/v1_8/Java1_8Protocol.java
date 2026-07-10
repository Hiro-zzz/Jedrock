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
    public static final int CB_CHAT = 0x02;
    public static final int CB_CHANGE_GAME_STATE = 0x2B; // reason byte + float value (gamemode switch)
    public static final int CB_UPDATE_HEALTH = 0x06;     // float health + food varint + saturation float
    public static final int CB_SPAWN_POSITION = 0x05;
    public static final int CB_POSITION = 0x08;          // player position and look (no teleport id in 1.8)
    public static final int CB_HELD_ITEM = 0x09;
    public static final int CB_ANIMATION = 0x0B;
    public static final int CB_SPAWN_PLAYER = 0x0C;       // named entity spawn (fixed-point coords)
    public static final int CB_ENTITY_DESTROY = 0x13;
    public static final int CB_ENTITY_TELEPORT = 0x18;   // fixed-point coords
    public static final int CB_ENTITY_HEAD_ROTATION = 0x19;
    public static final int CB_ENTITY_METADATA = 0x1C;
    public static final int CB_WINDOW_ITEMS = 0x30; // replace a window's contents (player inventory)
    public static final int CB_SET_SLOT = 0x2F;      // update one window slot
    public static final int CB_CHUNK_DATA = 0x21;
    public static final int CB_BLOCK_CHANGE = 0x23;
    public static final int CB_PLAYER_LIST_ITEM = 0x38;
    public static final int CB_PLAYER_ABILITIES = 0x39;
    public static final int CB_SERVER_DIFFICULTY = 0x41;

    // ===== PLAY (serverbound) =====
    public static final int SB_KEEP_ALIVE = 0x00;
    public static final int SB_CHAT = 0x01;
    public static final int SB_POSITION = 0x04;
    public static final int SB_LOOK = 0x05;
    public static final int SB_POSITION_LOOK = 0x06;
    public static final int SB_BLOCK_DIG = 0x07;
    public static final int SB_BLOCK_PLACE = 0x08;
    public static final int SB_HELD_ITEM = 0x09;
    public static final int SB_ARM_ANIMATION = 0x0A;
    public static final int SB_ENTITY_ACTION = 0x0B;

    // ===== 1.8 entity metadata (old format): header = (type << 5) | index, list ends with 0x7F =====
    public static final int META_TYPE_BYTE = 0;
    public static final int META_INDEX_FLAGS = 0;   // shared entity flags byte
    public static final int META_END = 0x7F;
    public static final int FLAG_CROUCHED = 0x02;
    public static final int FLAG_SPRINTING = 0x08;
    public static final int FLAG_USING_ITEM = 0x10;  // eating / drinking / blocking / drawing bow

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
