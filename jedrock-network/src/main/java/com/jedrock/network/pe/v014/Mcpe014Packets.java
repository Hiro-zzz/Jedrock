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
    public static final int ID_MOVE_PLAYER = 0x9d;
    public static final int ID_SET_HEALTH = 0xb0;
    public static final int ID_SET_SPAWN_POSITION = 0xb1;
    public static final int ID_RESPAWN = 0xb3;
    public static final int ID_FULL_CHUNK_DATA = 0xbf;
    public static final int ID_SET_DIFFICULTY = 0xc0;
    public static final int ID_REQUEST_CHUNK_RADIUS = 0xc8;
    public static final int ID_CHUNK_RADIUS_UPDATE = 0xc9;

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
}
