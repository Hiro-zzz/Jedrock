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
    public static final int ID_REMOVE_ENTITY = 0x99;
    public static final int ID_MOVE_ENTITY = 0x9c;
    public static final int ID_MOVE_PLAYER = 0x9d;
    public static final int ID_REMOVE_BLOCK = 0x9e;
    public static final int ID_UPDATE_BLOCK = 0x9f;
    public static final int ID_USE_ITEM = 0xaa;
    public static final int ID_PLAYER_ACTION = 0xab;
    public static final int ID_SET_ENTITY_DATA = 0xad;
    public static final int ID_SET_HEALTH = 0xb0;
    public static final int ID_SET_SPAWN_POSITION = 0xb1;
    public static final int ID_ANIMATE = 0xb2;
    public static final int ID_RESPAWN = 0xb3;
    public static final int ID_FULL_CHUNK_DATA = 0xbf;
    public static final int ID_SET_DIFFICULTY = 0xc0;
    public static final int ID_PLAYER_LIST = 0xc3;
    public static final int ID_REQUEST_CHUNK_RADIUS = 0xc8;
    public static final int ID_CHUNK_RADIUS_UPDATE = 0xc9;

    // PlayerList types.
    public static final int PLAYER_LIST_ADD = 0;
    public static final int PLAYER_LIST_REMOVE = 1;
    // Entity metadata (0.14): DATA_FLAGS is a BYTE at index 0 (not a LONG like 1.1.5); terminator 0x7f.
    public static final int DATA_FLAGS_INDEX = 0;
    public static final int DATA_TYPE_BYTE = 0;
    public static final int META_END = 0x7f;
    public static final int FLAG_SNEAKING = 1 << 1; // 0x02
    public static final int FLAG_SPRINTING = 1 << 3; // 0x08
    public static final int FLAG_ACTION = 1 << 4;   // 0x10 — using an item

    // TextPacket types.
    public static final int TEXT_TYPE_RAW = 0;
    public static final int TEXT_TYPE_CHAT = 1;
    // MovePlayer modes.
    public static final int MOVE_MODE_NORMAL = 0;
    public static final int MOVE_MODE_RESET = 1;
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

    /** Move an entity (avatar). {@code y} must be EYE-level (feet + {@link #EYE_HEIGHT}). */
    public static void moveEntity(ByteBuf b, long eid, float x, float y, float z, float yaw, float pitch) {
        b.writeByte(ID_MOVE_ENTITY);
        b.writeInt(1);             // entity count
        b.writeLong(eid);
        b.writeFloat(x);
        b.writeFloat(y);
        b.writeFloat(z);
        b.writeFloat(yaw);
        b.writeFloat(yaw);         // head yaw
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

    /** Add one entry to the pause-menu player list. Skin name/data left empty (0.14 draws its own). */
    public static void playerListAdd(ByteBuf b, java.util.UUID uuid, long eid, String name) {
        b.writeByte(ID_PLAYER_LIST);
        b.writeByte(PLAYER_LIST_ADD);
        b.writeInt(1);                 // entry count
        Mcpe014Codec.writeUuid(b, uuid);
        b.writeLong(eid);
        Mcpe014Codec.writeString(b, name);
        Mcpe014Codec.writeString(b, ""); // skin name / geometry
        Mcpe014Codec.writeString(b, ""); // skin data
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
        int flags = (sneaking ? FLAG_SNEAKING : 0) | (sprinting ? FLAG_SPRINTING : 0)
                | (usingItem ? FLAG_ACTION : 0);
        b.writeByte(ID_SET_ENTITY_DATA);
        b.writeLong(eid);
        b.writeByte((DATA_TYPE_BYTE << 5) | DATA_FLAGS_INDEX); // metadata key header
        b.writeByte(flags);
        b.writeByte(META_END);
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
