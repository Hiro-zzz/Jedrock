package com.jedrock.network.pe;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.EntityFlagIds;
import com.jedrock.network.EntityTypeIds;
import com.jedrock.network.chunk.ChunkView;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetReliability;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetSessionListener;
import com.nukkitx.network.raknet.RakNetState;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.jedrock.network.pe.McpeProtocol.*;

/**
 * One Bedrock (PE 1.1.5) player session: the RakNet session callbacks, the MCPE game-layer state
 * machine that walks a client from Login to spawn, AND the {@link PlayerConnection} the core sees —
 * so a Bedrock player lands in the same PlayerRegistry as a Java one.
 *
 * <p>Wire concerns are delegated out: {@link McpeCodec} frames packets and items, {@link McpeSkin}
 * builds the placeholder avatar texture, {@link McpeChunkSerializer} serializes chunk columns,
 * {@link McpeLoginIdentity} pulls the gamertag from the Login JWTs, and {@link PeBlockEditDecoder}
 * turns inbound break/place packets into edits. This class owns only the session state and flow.
 */
final class PeSession implements RakNetSessionListener, PlayerConnection {

    private static final JLogger LOGGER = JLogger.getLogger(PeSession.class);

    /** Max chunk view radius we honour from the client's RequestChunkRadius (join cost vs. distance). */
    private static final int MAX_VIEW_RADIUS = 4;

    private final RakNetServerSession session;
    private final ConnectionListener listener;
    private final ProtocolVersion protocol;
    private final World world;
    private final ServerProperties properties;
    /** Shared uuid → skin map (owned by the PE server); lets us render other PE players' real skins. */
    private final Map<UUID, McpeSkin.Skin> skins;

    private volatile boolean loggedIn = false;
    private volatile UUID uuid;
    private volatile String username;
    /** This player's own skin (real one from the Login JWT, or synthetic); published to {@link #skins}. */
    private volatile McpeSkin.Skin skin;
    /** Compression mode observed on inbound batches; reused for outbound (chat etc.). */
    private volatile boolean rawDeflate = false;

    /** Chunk streaming state; created once the client's requested radius is known. Volatile: a chest
     *  placed by another player reads it from that editor's thread to push a targeted chunk refresh. */
    private volatile ChunkView chunkView;
    private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
        @Override public void load(int cx, int cz) { sendChunk(cx, cz); }
        @Override public void unload(int cx, int cz) { /* PE 1.1.5 client culls by distance */ }
    };

    PeSession(RakNetServerSession session, ConnectionListener listener, ProtocolVersion protocol,
              World world, ServerProperties properties, Map<UUID, McpeSkin.Skin> skins) {
        this.session = session;
        this.listener = listener;
        this.protocol = protocol;
        this.world = world;
        this.properties = properties;
        this.skins = skins;
    }

    // ===== PlayerConnection (api) — lets the core treat a PE player like any other =====

    @Override
    public ProtocolVersion getProtocolVersion() {
        return protocol;
    }

    @Override
    public String getAddress() {
        return String.valueOf(session.getAddress());
    }

    @Override
    public boolean isActive() {
        return !session.isClosed();
    }

    @Override
    public void close(String reason) {
        session.disconnect();
    }

    @Override
    public void sendPacket(Object packet) {
        // Typed outbound PE packets are future work; the core mainly needs identity + lifecycle.
    }

    @Override
    public void sendMessage(String message) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_TEXT);
            b.writeByte(TEXT_TYPE_RAW);
            ByteBufUtils.writeString(b, message);
        });
    }

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // Native SetTitle (0x59), byte-verified against PMMP at protocol 113. Send the animation times, then
        // the subtitle, then the title (the title packet triggers the on-screen display).
        sendGameBatch(b -> writeSetTitle(b, TITLE_TYPE_TIMES, "", fadeIn, stay, fadeOut));
        if (subtitle != null && !subtitle.isEmpty()) {
            sendGameBatch(b -> writeSetTitle(b, TITLE_TYPE_SUBTITLE, subtitle, fadeIn, stay, fadeOut));
        }
        sendGameBatch(b -> writeSetTitle(b, TITLE_TYPE_TITLE, title == null ? "" : title, fadeIn, stay, fadeOut));
    }

    @Override
    public void sendActionBar(String text) {
        // The action bar is its own SetTitle type; give it modest fade/stay so it lingers ~1s per call.
        sendGameBatch(b -> writeSetTitle(b, TITLE_TYPE_ACTIONBAR, text == null ? "" : text, 1, 20, 1));
    }

    @Override
    public void clearTitle() {
        sendGameBatch(b -> writeSetTitle(b, TITLE_TYPE_CLEAR, "", 0, 0, 0));
    }

    /**
     * Write one SetTitle (0x59) packet. Layout, verbatim from PMMP {@code SetTitlePacket} at protocol 113:
     * {@code type} (signed varint), {@code text} (string), then {@code fadeIn} / {@code stay} /
     * {@code fadeOut} (signed varints, in ticks). {@code type} is one of the {@code TITLE_TYPE_*} values.
     */
    static void writeSetTitle(ByteBuf b, int type, String text, int fadeIn, int stay, int fadeOut) {
        ByteBufUtils.writeVarInt(b, ID_SET_TITLE);
        ByteBufUtils.writeSignedVarInt(b, type);
        ByteBufUtils.writeString(b, text == null ? "" : text);
        ByteBufUtils.writeSignedVarInt(b, fadeIn);
        ByteBufUtils.writeSignedVarInt(b, stay);
        ByteBufUtils.writeSignedVarInt(b, fadeOut);
    }

    @Override
    public int getPing() {
        // RakNet keeps its own connected-ping estimate per session — no game-layer probe needed.
        return (int) Math.min(Integer.MAX_VALUE, session.getPing());
    }

    /** LevelEvent rain/thunder intensity — the mid value the era's servers used; stops send 0. */
    private static final int WEATHER_INTENSITY = 10000;

    @Override
    public void sendWeather(com.jedrock.api.world.Weather weather) {
        // LevelEvent 3001 start rain / 3002 start thunder / 3003 stop rain / 3004 stop thunder.
        // Weather events carry no coordinates (PMMP: "Weather effects don't have coordinates").
        switch (weather) {
            case CLEAR -> sendGameBatch(
                    b -> writeLevelEvent(b, 3003, 0, 0, 0, 0),
                    b -> writeLevelEvent(b, 3004, 0, 0, 0, 0));
            case RAIN -> sendGameBatch(
                    b -> writeLevelEvent(b, 3001, 0, 0, 0, WEATHER_INTENSITY),
                    b -> writeLevelEvent(b, 3004, 0, 0, 0, 0));
            case THUNDER -> sendGameBatch(
                    b -> writeLevelEvent(b, 3001, 0, 0, 0, WEATHER_INTENSITY),
                    b -> writeLevelEvent(b, 3002, 0, 0, 0, WEATHER_INTENSITY));
        }
    }

    @Override
    public void playSound(com.jedrock.api.world.Sound sound, double x, double y, double z, float volume, float pitch) {
        int evid = PeEffects.levelEventSound113(sound);
        if (evid >= 0) {
            // LevelEvent sounds carry pitch in the data field ×1000 (PMMP GenericSound); no volume slot.
            int data = Math.round(pitch * 1000f);
            sendGameBatch(b -> writeLevelEvent(b, evid, x, y, z, data));
        } else {
            int soundId = PeEffects.levelSound113(sound);
            sendGameBatch(b -> writeLevelSoundEvent(b, soundId, x, y, z));
        }
    }

    /** PE draws one particle per LevelEvent packet — cap a burst so a script can't flood the wire. */
    private static final int MAX_PARTICLE_BURST = 32;

    @Override
    public void spawnParticle(com.jedrock.api.world.Particle particle, double x, double y, double z,
                              int count, double spread) {
        int evid = PeEffects.ADD_PARTICLE_MASK | PeEffects.particle113(particle);
        int n = Math.min(Math.max(1, count), MAX_PARTICLE_BURST);
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            final double px = x + offset(rnd, spread), py = y + offset(rnd, spread), pz = z + offset(rnd, spread);
            sendGameBatch(b -> writeLevelEvent(b, evid, px, py, pz, 0));
        }
    }

    /** A uniform scatter in ±spread (0 spread → exactly at the point). */
    private static double offset(java.util.concurrent.ThreadLocalRandom rnd, double spread) {
        return spread <= 0 ? 0 : (rnd.nextDouble() * 2.0 - 1.0) * spread;
    }

    /**
     * Write one LevelEvent (0x1a) packet. Layout, verbatim from PMMP {@code LevelEventPacket} at
     * protocol 113: event id (signed varint — a 1000-series sound or {@code 0x4000 | particle type}),
     * position (Vector3f = 3 LE floats), data (signed varint — pitch×1000 for sounds, 0 for particles).
     */
    static void writeLevelEvent(ByteBuf b, int evid, double x, double y, double z, int data) {
        ByteBufUtils.writeVarInt(b, ID_LEVEL_EVENT);
        ByteBufUtils.writeSignedVarInt(b, evid);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);
        b.writeFloatLE((float) z);
        ByteBufUtils.writeSignedVarInt(b, data);
    }

    /**
     * Write one LevelSoundEvent (0x19) packet. Layout, verbatim from PMMP {@code LevelSoundEventPacket}
     * at protocol 113: sound (byte), position (Vector3f), extraData (signed varint, -1 = none), pitch
     * (signed varint, 1 = normal), isBabyMob (bool), disableRelativeVolume (bool).
     */
    static void writeLevelSoundEvent(ByteBuf b, int soundId, double x, double y, double z) {
        ByteBufUtils.writeVarInt(b, ID_LEVEL_SOUND_EVENT);
        b.writeByte(soundId);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);
        b.writeFloatLE((float) z);
        ByteBufUtils.writeSignedVarInt(b, -1);
        ByteBufUtils.writeSignedVarInt(b, 1);
        b.writeBoolean(false);
        b.writeBoolean(false);
    }

    @Override
    public void addToTab(UUID uuid, String name) {
        // The PE pause-menu list is fed by showPlayer's PlayerList entry (it needs an entity id +
        // skin, which this signature doesn't carry). No separate tab packet.
    }

    @Override
    public void removeFromTab(UUID uuid) {
        // See addToTab; hidePlayer removes the PlayerList entry.
    }

    @Override
    public void showPlayer(UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        // The shown player's real skin if they're a Bedrock player; synthetic for JE players.
        McpeSkin.Skin shownSkin = skins.getOrDefault(uuid, McpeSkin.syntheticSkin(uuid));
        // PlayerList ADD must precede AddPlayer — it carries the skin the avatar renders with.
        sendGameBatch(
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
                    b.writeByte(PLAYER_LIST_ADD);
                    ByteBufUtils.writeVarInt(b, 1);                    // entry count
                    McpeCodec.writeUuid(b, uuid);
                    ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
                    ByteBufUtils.writeString(b, name);
                    ByteBufUtils.writeString(b, shownSkin.id());       // skin id / geometry
                    ByteBufUtils.writeByteArray(b, shownSkin.data());  // skin RGBA texture
                },
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_ADD_PLAYER);
                    McpeCodec.writeUuid(b, uuid);
                    ByteBufUtils.writeString(b, name);
                    ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
                    ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
                    b.writeFloatLE((float) x);
                    b.writeFloatLE((float) y);                         // AddPlayer takes feet y
                    b.writeFloatLE((float) z);
                    b.writeFloatLE(0f);                                // motion x
                    b.writeFloatLE(0f);                                // motion y
                    b.writeFloatLE(0f);                                // motion z
                    b.writeFloatLE(pitch);
                    b.writeFloatLE(yaw);                               // head yaw
                    b.writeFloatLE(yaw);
                    ByteBufUtils.writeSignedVarInt(b, 0);              // held item: air
                    // Entity metadata: the flags long (nametag always visible) + the nametag string,
                    // so the player's name floats above the avatar the way it does on Java.
                    ByteBufUtils.writeVarInt(b, 2);                    // metadata entry count
                    ByteBufUtils.writeVarInt(b, DATA_FLAGS_INDEX);     // key = DATA_FLAGS (0)
                    ByteBufUtils.writeVarInt(b, DATA_TYPE_LONG);       // type = LONG (7)
                    ByteBufUtils.writeSignedVarLong(b, BASE_ENTITY_FLAGS);
                    ByteBufUtils.writeVarInt(b, DATA_NAMETAG_INDEX);   // key = DATA_NAMETAG (4)
                    ByteBufUtils.writeVarInt(b, DATA_TYPE_STRING);     // type = STRING (4)
                    ByteBufUtils.writeString(b, name);                 // the floating nametag
                });
    }

    @Override
    public void hidePlayer(UUID uuid, long entityId) {
        sendGameBatch(
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_REMOVE_ENTITY);
                    ByteBufUtils.writeSignedVarLong(b, entityId);
                },
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
                    b.writeByte(PLAYER_LIST_REMOVE);
                    ByteBufUtils.writeVarInt(b, 1);
                    McpeCodec.writeUuid(b, uuid);
                });
    }

    @Override
    public void spawnEntity(long entityId, UUID uuid, com.jedrock.api.entity.EntityType type,
                            double x, double y, double z, float yaw, float pitch) {
        // A puppet carries the nametag-visibility flags from birth, so a later setEntityNameTag shows up
        // without having to re-send the flags (they share one DATA_FLAGS long).
        int typeId = EntityTypeIds.bedrockId(type);
        sendGameBatch(b -> writeAddEntity(b, entityId, typeId, x, y, z, yaw, pitch,
                meta -> {
                    ByteBufUtils.writeVarInt(meta, 1);         // metadata entry count
                    writeFlagsEntry(meta, BASE_ENTITY_FLAGS);
                }));
    }

    @Override
    public void spawnTextLine(long entityId, UUID uuid, double x, double y, double z, String text) {
        sendGameBatch(b -> writeAddTextLine(b, entityId, x, y, z, text));
    }

    /**
     * Encode one hologram line: an item entity with no item — nothing renders but the name floating where
     * the body would be, immobile so it can't be nudged. PocketMine's own floating-text hack, which
     * deliberately does <em>not</em> set the invisible flag at protocol 113: an item entity that was never
     * given an item draws nothing anyway.
     */
    static void writeAddTextLine(ByteBuf b, long entityId, double x, double y, double z, String text) {
        long flags = BASE_ENTITY_FLAGS | (1L << DATA_FLAG_IMMOBILE_BIT);
        writeAddEntity(b, entityId, ITEM_ENTITY_TYPE_ID, x, y + TEXT_LINE_Y_OFFSET, z, 0f, 0f,
                meta -> {
                    ByteBufUtils.writeVarInt(meta, 2);         // metadata entry count
                    writeFlagsEntry(meta, flags);
                    writeNameTagEntry(meta, text);
                });
    }

    @Override
    public void setEntityNameTag(long entityId, String nameTag) {
        // Only the nametag string: metadata is a merge, and the visibility bits already rode in on spawn.
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_SET_ENTITY_DATA);
            ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
            ByteBufUtils.writeVarInt(b, 1);                    // metadata entry count
            writeNameTagEntry(b, nameTag);
        });
    }

    @Override
    public void setEntityFlags(long entityId, int flags) {
        // DATA_FLAGS is one long holding both the canonical flags and the nametag-visibility bits, so it
        // is always written whole — dropping the base bits here would silently hide the puppet's name.
        long bits = BASE_ENTITY_FLAGS | EntityFlagIds.bedrockBits(flags);
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_SET_ENTITY_DATA);
            ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
            ByteBufUtils.writeVarInt(b, 1);                    // metadata entry count
            writeFlagsEntry(b, bits);
        });
    }

    /**
     * Encode an AddEntity body (0x0D): entity ids, the MCPE entity type, feet position, motion, rotation
     * (pitch before yaw), then attributes, the metadata dictionary and entity links. Verbatim from
     * PocketMine-MP at protocol 113 ({@code AddEntityPacket::encodePayload}).
     */
    static void writeAddEntity(ByteBuf b, long entityId, int typeId,
                               double x, double y, double z, float yaw, float pitch,
                               Consumer<ByteBuf> metadata) {
        ByteBufUtils.writeVarInt(b, ID_ADD_ENTITY);
        ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
        ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
        ByteBufUtils.writeVarInt(b, typeId);               // entity type (uvarint, MCPE id)
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);                         // feet
        b.writeFloatLE((float) z);
        b.writeFloatLE(0f);                                // motion x
        b.writeFloatLE(0f);                                // motion y
        b.writeFloatLE(0f);                                // motion z
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);
        ByteBufUtils.writeVarInt(b, 0);                    // attributes: none
        metadata.accept(b);                                // metadata dictionary (count + entries)
        ByteBufUtils.writeVarInt(b, 0);                    // entity links: none
    }

    /** One DATA_FLAGS metadata entry (a zigzag long). */
    private static void writeFlagsEntry(ByteBuf b, long flags) {
        ByteBufUtils.writeVarInt(b, DATA_FLAGS_INDEX);     // key = DATA_FLAGS (0)
        ByteBufUtils.writeVarInt(b, DATA_TYPE_LONG);       // type = LONG (7)
        ByteBufUtils.writeSignedVarLong(b, flags);         // putVarLong (zigzag)
    }

    /** One DATA_NAMETAG metadata entry — index 4 at protocol 113 (0.14 puts it at 2). */
    private static void writeNameTagEntry(ByteBuf b, String nameTag) {
        ByteBufUtils.writeVarInt(b, DATA_NAMETAG_INDEX);   // key = DATA_NAMETAG (4)
        ByteBufUtils.writeVarInt(b, DATA_TYPE_STRING);     // type = STRING (4)
        ByteBufUtils.writeString(b, nameTag == null ? "" : nameTag);
    }

    @Override
    public void moveEntity(long entityId, double x, double y, double z, float yaw, float pitch) {
        // MoveEntity (0x12): runtime id, feet position, then byte-angle pitch / yaw / headYaw and a flags
        // byte. Unlike MovePlayer (0x13, player-only) this addresses any entity runtime id.
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_MOVE_ENTITY);
            ByteBufUtils.writeVarLong(b, entityId);
            b.writeFloatLE((float) x);
            b.writeFloatLE((float) y);                         // feet
            b.writeFloatLE((float) z);
            ByteBufUtils.writeAngle(b, pitch);
            ByteBufUtils.writeAngle(b, yaw);
            ByteBufUtils.writeAngle(b, yaw);                    // head yaw
            b.writeByte(0);                                     // flags (on-ground / teleport)
        });
    }

    @Override
    public void removeEntity(long entityId) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_REMOVE_ENTITY);
            ByteBufUtils.writeSignedVarLong(b, entityId);
        });
    }

    @Override
    public void sendBlockChange(int x, int y, int z, int state) {
        writeUpdateBlock(x, y, z, state);
        // A chest is a block-entity, and the retail 1.1.5 client materializes it ONLY from chunk data —
        // a standalone BlockEntityData won't do (verified: it still crashes on open). A freshly placed
        // chest isn't in the already-sent chunk, so re-send that whole column now that the world holds the
        // chest; the serializer writes the tile into the chunk tail, giving the client something to bind
        // the GUI to. Only when this session actually holds the chunk — sendBlockChange runs on the
        // editor's thread, so isLoaded (thread-safe) gates a foreign edit from pushing an off-view chunk.
        if (Blocks.idOf(state) == Blocks.CHEST && chunkView != null
                && chunkView.isLoaded(x >> 4, z >> 4)) {
            sendChunk(x >> 4, z >> 4);
        }
    }

    /**
     * Write one typed UpdateBlock (0x16): a single block instead of re-sending the whole chunk. Position is
     * x/z zigzag-varint and y unsigned-varint (the layout the inbound edit decoder reads); the canonical
     * state splits into a legacy id and 4-bit meta. Used for a normal (server-authored) edit the client has
     * no opinion about — the retail 1.1.5 client honours those, but NOT a correction to a cell it edited
     * itself (see {@link #resyncAround}, which re-sends the chunk instead).
     */
    private void writeUpdateBlock(int x, int y, int z, int state) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_UPDATE_BLOCK);
            ByteBufUtils.writeSignedVarInt(b, x);
            ByteBufUtils.writeVarInt(b, y);
            ByteBufUtils.writeSignedVarInt(b, z);
            ByteBufUtils.writeVarInt(b, (state >> 4) & 0xFF);                            // legacy block id
            ByteBufUtils.writeVarInt(b, (UPDATE_BLOCK_FLAG_ALL << 4) | (state & 0xF));   // (flags << 4) | meta
        });
    }

    @Override
    public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_MOVE_PLAYER);
            ByteBufUtils.writeVarLong(b, entityId);
            b.writeFloatLE((float) x);
            b.writeFloatLE((float) (y + EYE_HEIGHT));          // MovePlayer takes eye y
            b.writeFloatLE((float) z);
            b.writeFloatLE(pitch);
            b.writeFloatLE(yaw);                               // head yaw
            b.writeFloatLE(yaw);
            b.writeByte(0);                                    // mode: normal (interpolated)
            b.writeBoolean(true);                              // on ground
            ByteBufUtils.writeVarLong(b, 0);                   // riding runtime id: none
        });
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        // Reposition our own player via MovePlayer in teleport mode (the judge snapping back a move).
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_MOVE_PLAYER);
            ByteBufUtils.writeVarLong(b, SELF_ENTITY_ID);      // our own runtime id
            b.writeFloatLE((float) x);
            b.writeFloatLE((float) (y + EYE_HEIGHT));          // MovePlayer takes eye y
            b.writeFloatLE((float) z);
            b.writeFloatLE(pitch);
            b.writeFloatLE(yaw);                               // head yaw
            b.writeFloatLE(yaw);
            b.writeByte(MOVE_MODE_TELEPORT);                   // server-forced reposition
            b.writeBoolean(true);                              // on ground
            ByteBufUtils.writeVarLong(b, 0);                   // riding runtime id: none
        });
    }

    @Override
    public void swingArm(long entityId) {
        sendGameBatch(b -> writeAnimate(b, ANIMATE_SWING_ARM, entityId));
    }

    @Override
    public void playHurtAnimation(long entityId) {
        sendGameBatch(b -> writeEntityEvent(b, entityId, ENTITY_EVENT_HURT, 0));
    }

    /**
     * Encode an EntityEvent body (protocol 113): entity runtime id, byte event, then a {@code putVarInt}
     * data field (signed). {@code event} 2 = hurt (the damage flash + sound); {@code data} is unused for it.
     */
    static void writeEntityEvent(ByteBuf b, long entityRuntimeId, int event, int data) {
        ByteBufUtils.writeVarInt(b, ID_ENTITY_EVENT);
        ByteBufUtils.writeVarLong(b, entityRuntimeId); // entity runtime id
        b.writeByte(event);
        ByteBufUtils.writeSignedVarInt(b, data);       // data (putVarInt, signed)
    }

    @Override
    public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        sendGameBatch(b -> writeSetEntityDataFlags(b, entityId, sneaking, sprinting, usingItem));
    }

    @Override
    public void showHeldItem(long entityId, int state) {
        sendGameBatch(b -> writeMobEquipment(b, entityId, state));
    }

    /**
     * Encode a MobEquipment (0x1f) body, verbatim from PMMP at protocol 113: entity runtime id
     * (unsigned varlong), the item Slot, inventorySlot (byte), hotbarSlot (byte), windowId (byte 0).
     * The slot bytes only matter for the holder's own inventory; a viewer just renders the item.
     */
    static void writeMobEquipment(ByteBuf b, long entityRuntimeId, int state) {
        ByteBufUtils.writeVarInt(b, ID_MOB_EQUIPMENT);
        ByteBufUtils.writeVarLong(b, entityRuntimeId);
        McpeCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        b.writeByte(0);
        b.writeByte(0);
        b.writeByte(0);
    }

    /** Encode an Animate packet body (protocol 113): action (putVarInt), then entity runtime id. */
    static void writeAnimate(ByteBuf b, int action, long entityRuntimeId) {
        ByteBufUtils.writeVarInt(b, ID_ANIMATE);
        ByteBufUtils.writeSignedVarInt(b, action);     // action (putVarInt, signed)
        ByteBufUtils.writeVarLong(b, entityRuntimeId); // entity runtime id
        // no trailing float — only actions with bit 0x80 carry one
    }

    /**
     * Encode a SetEntityData body carrying the pose: entity runtime id, then a one-entry metadata
     * dictionary DATA_FLAGS (a long) with the sneaking / sprinting bits set (both together, since
     * they live in the same long).
     */
    static void writeSetEntityDataFlags(ByteBuf b, long entityRuntimeId,
                                        boolean sneaking, boolean sprinting, boolean usingItem) {
        // Keep the nametag-visibility flags on every write, or updating the pose would hide the name.
        long flags = BASE_ENTITY_FLAGS
                | (sneaking ? (1L << DATA_FLAG_SNEAKING_BIT) : 0L)
                | (sprinting ? (1L << DATA_FLAG_SPRINTING_BIT) : 0L)
                | (usingItem ? (1L << DATA_FLAG_ACTION_BIT) : 0L);
        ByteBufUtils.writeVarInt(b, ID_SET_ENTITY_DATA);
        ByteBufUtils.writeVarLong(b, entityRuntimeId); // entity runtime id
        ByteBufUtils.writeVarInt(b, 1);                // metadata entry count
        ByteBufUtils.writeVarInt(b, DATA_FLAGS_INDEX); // key = DATA_FLAGS (0)
        ByteBufUtils.writeVarInt(b, DATA_TYPE_LONG);   // type = LONG (7)
        ByteBufUtils.writeSignedVarLong(b, flags);     // putVarLong (zigzag)
    }

    // ===== RakNet session callbacks =====

    @Override
    public void onSessionChangeState(RakNetState state) {
        LOGGER.info("[PE] " + session.getAddress() + " -> " + state);
        if (state == RakNetState.CONNECTED) {
            LOGGER.info("[PE] RakNet CONNECTED: " + session.getAddress() + " — awaiting MCPE Login");
        }
    }

    @Override
    public void onDisconnect(DisconnectReason reason) {
        LOGGER.info("[PE] disconnect " + session.getAddress() + " (" + reason + ")");
        if (uuid != null) {
            skins.remove(uuid);
        }
        if (loggedIn && listener != null) {
            listener.onDisconnect(this);
        }
    }

    @Override
    public void onDirect(ByteBuf buf) {
        // Raw, non-encapsulated data — not expected in normal flow.
    }

    @Override
    public void onEncapsulated(EncapsulatedPacket packet) {
        // Copy the payload out without consuming the library's buffer.
        ByteBuf buf = packet.getBuffer();
        int size = buf.readableBytes();
        if (size < 1) return;
        byte[] payload = new byte[size];
        buf.getBytes(buf.readerIndex(), payload);

        if ((payload[0] & 0xFF) != GAME_PACKET_WRAPPER) {
            LOGGER.debug(() -> "[PE] non-game payload id=0x" + Integer.toHexString(payload[0] & 0xFF));
            return;
        }
        decodeBatch(Arrays.copyOfRange(payload, 1, payload.length));
    }

    /** Inflate a 0xFE batch and dispatch each inner MCPE packet. */
    private void decodeBatch(byte[] compressed) {
        McpeCompression.Inflated inflated = McpeCompression.inflate(compressed);
        if (inflated == null) {
            LOGGER.warn("[PE] failed to inflate game batch (" + compressed.length + " bytes)");
            return;
        }
        this.rawDeflate = inflated.raw();

        ByteBuf batch = Unpooled.wrappedBuffer(inflated.data());
        try {
            int packetCount = 0;
            while (batch.isReadable()) {
                if (++packetCount > PacketGuard.MAX_PACKETS_PER_BATCH) {
                    LOGGER.warn("[PE] batch exceeded " + PacketGuard.MAX_PACKETS_PER_BATCH
                            + " inner packets — dropping the rest");
                    break;
                }
                int len = ByteBufUtils.readVarInt(batch);
                if (len <= 0 || len > batch.readableBytes()) {
                    LOGGER.warn("[PE] malformed batch entry: len=" + len);
                    break;
                }
                ByteBuf pk = batch.readSlice(len);
                int id = ByteBufUtils.readVarInt(pk);
                LOGGER.debug(() -> "[PE] inbound packet id=0x" + Integer.toHexString(id));
                // Offer the raw packet to any tap; a cancel drops it before the session handles it.
                if (listener != null && listener.hasPacketTaps()) {
                    byte[] body = new byte[pk.readableBytes()];
                    pk.getBytes(pk.readerIndex(), body); // absolute read — doesn't consume
                    if (listener.onInboundPacket(this, id, body)) {
                        continue; // cancelled — the slice is freed with the batch
                    }
                }
                handleGamePacket(id, pk);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[PE] error parsing batch: " + e.getMessage());
        } finally {
            batch.release();
        }
    }

    private void handleGamePacket(int id, ByteBuf pk) {
        switch (id) {
            case ID_LOGIN -> {
                McpeLoginIdentity.Identity identity = McpeLoginIdentity.extract(pk);
                this.uuid = identity.uuid();
                this.username = identity.name();
                this.skin = buildSkin(identity);
                LOGGER.info("[PE] Login: " + username + " (" + uuid + ", skin="
                        + (identity.skinData() != null ? "real" : "synthetic") + ") → PlayStatus + ResourcePacksInfo");
                sendLoginResponse();
            }
            case ID_RESOURCE_PACK_RESPONSE -> {
                LOGGER.info("[PE] resource packs accepted → StartGame");
                sendStartGame();
            }
            case ID_REQUEST_CHUNK_RADIUS -> {
                int requested = ByteBufUtils.readSignedVarInt(pk);
                int radius = Math.clamp(requested, 2, MAX_VIEW_RADIUS);
                LOGGER.info("[PE] chunk radius requested (" + requested + ") → streaming r=" + radius + " + spawn");
                sendWorld(radius);
                registerPlayer(); // fully in-game now — hand it to the core like a JE player
            }
            case ID_TEXT -> handleInboundText(pk);
            case ID_COMMAND_STEP -> handleCommandStep(pk);
            case ID_MOVE_PLAYER -> handleInboundMove(pk);
            // NB: id 0x1E is UpdateAttributes (server→client), not an inbound InventoryTransaction — that
            // packet doesn't exist at protocol 113. The Win10 1.1.5 client reports edits via UseItem
            // (place) and PlayerAction (break), handled below; there was never a real 0x1E edit to decode.
            case ID_MOB_EQUIPMENT -> handleMobEquipment(pk);
            case ID_USE_ITEM -> handleUseItem(pk);
            case ID_CONTAINER_SET_SLOT -> handleInboundContainerSetSlot(pk);
            case ID_CONTAINER_CLOSE -> handleInboundContainerClose(pk);
            case ID_PLAYER_ACTION -> handlePlayerAction(pk);
            case ID_INTERACT -> handleInteract(pk);
            case ID_ENTITY_FALL -> handleEntityFall(pk);
            case ID_ANIMATE -> handleInboundAnimate(pk);
            default -> { /* other gameplay packet — nothing to answer yet */ }
        }
    }

    /** Register the fully-joined player in the core, exactly once. */
    private void registerPlayer() {
        if (loggedIn || uuid == null) return;
        loggedIn = true;
        // Publish our skin before onLogin so the other players' showPlayer(us) can render it.
        if (skin != null) {
            skins.put(uuid, skin);
        }
        if (listener != null) {
            listener.onLogin(this, uuid, username);
        }
    }

    /** Build this player's skin from the Login JWT, falling back to the synthetic one. */
    private static McpeSkin.Skin buildSkin(McpeLoginIdentity.Identity identity) {
        if (identity.skinData() != null) {
            String id = identity.skinId() != null ? identity.skinId() : McpeSkin.DEFAULT_SKIN_ID;
            return new McpeSkin.Skin(id, identity.skinData());
        }
        return McpeSkin.syntheticSkin(identity.uuid());
    }

    /** Relay a decoded block edit to the core, if we are in-game. */
    private void applyEdit(PeBlockEditDecoder.BlockEdit edit) {
        if (edit != null && loggedIn && listener != null) {
            listener.onBlockChange(this, edit.x(), edit.y(), edit.z(), edit.state());
        }
    }

    /**
     * Break a block for a creative instant-mine, debounced. The 1.1.5 client fires START_BREAK then a
     * stream of CONTINUE_BREAK on the same cell; without this each would post its own onBlockChange —
     * spamming BlockBreakEvent (and, near a protected region, a flood of "cancel" corrections that fight
     * the client's optimistic view and leave it desynced). breakDebounce collapses the burst to one edit.
     */
    private void breakBlock(int x, int y, int z) {
        long now = System.nanoTime();
        long gapMs = (now - lastEditLogNanos) / 1_000_000L;
        lastEditLogNanos = now;
        boolean applied = breakDebounce.accept(x, y, z, now);
        LOGGER.debug(() -> String.format("[PE] break target=%d,%d,%d gap=%dms %s",
                x, y, z, gapMs, applied ? "APPLIED" : "dropped"));
        if (applied) {
            listener.onBlockChange(this, x, y, z, Blocks.AIR);
            // The instant-break client also optimistically breaks the block the ray continues onto (a
            // neighbour it never told the server about). Re-assert the real neighbourhood so that ghost
            // is restored and one break leaves one hole. (1.1.5 only; runs after the world is updated.)
            resyncAround(x, y, z);
        }
    }

    // Ghost correction: chunks touched by this editor's edits, pending a re-send, plus the armed
    // trailing-edge flush. See resyncAround for why a chunk re-send (not an UpdateBlock) and why trailing.
    private final Set<Long> dirtyResyncChunks = new HashSet<>();
    private ScheduledFuture<?> resyncFlush;
    private static final long RESYNC_DELAY_MS = Long.getLong("jedrock.pe.resyncDelayMs", 180L);

    /**
     * Schedule a correction of any optimistic ghost this editor drew near {@code x,y,z} by re-sending the
     * affected chunk — but only once the edits settle.
     *
     * <p>The retail 1.1.5 client draws its own edits optimistically before the server replies — the
     * "staircase" second block of a placement, the block behind an instant-break, a break the server
     * rejected (spawn-protected / out of reach) — and it does <b>not</b> honour a standalone
     * {@code UpdateBlock} that contradicts one of its own edited cells, not even with the PRIORITY flag
     * (client-verified). The one thing it always trusts is <b>chunk data</b> (the same trait that makes
     * chests need a chunk tile), so the only reliable correction is to re-send the whole column.
     *
     * <p>Crucially the re-send must be <b>trailing-edge</b>: sending mid-burst carries a world state
     * <em>older</em> than the client's own in-flight optimistic edits (which the server hasn't received
     * yet), so a just-broken block would reappear. Instead we mark the chunk dirty and (re)arm one flush
     * that fires only after {@code RESYNC_DELAY_MS} of quiet — by then the server has caught up, so the
     * chunk reflects the final state, and a whole burst costs a single re-send. Runs on the session's
     * event loop (same thread as inbound), so the dirty set needs no locking. (1.1.5 only.)
     */
    private void resyncAround(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (chunkView == null || !chunkView.isLoaded(cx, cz)) {
            return; // the client isn't showing this chunk — nothing to correct
        }
        dirtyResyncChunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
        if (resyncFlush != null) {
            resyncFlush.cancel(false); // push the flush out to the trailing edge of the burst
        }
        resyncFlush = session.getEventLoop().schedule(this::flushResync, RESYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Re-send every chunk this editor touched since the last flush, now that its edits have settled. */
    private void flushResync() {
        resyncFlush = null;
        if (!loggedIn || chunkView == null) {
            dirtyResyncChunks.clear();
            return;
        }
        for (long key : dirtyResyncChunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            if (chunkView.isLoaded(cx, cz)) {
                sendChunk(cx, cz);
            }
        }
        dirtyResyncChunks.clear();
    }

    // Edit debounce: a single action on the Win10 1.1.5 client emits SEVERAL packets (0.14 sends one, so
    // this is purely the 1.1.5 client). PeEditDebounce collapses each burst to one edit while letting a
    // steady stream of deliberate edits through. Placement double-fires as a "staircase" of distinct cells
    // (burst rule); a creative break double-fires as START + a CONTINUE stream on the same cell (same-cell
    // rule only, so fast-mining distinct blocks isn't dropped). Tunable via -Djedrock.pe.*Ms.
    private final PeEditDebounce placeDebounce = new PeEditDebounce();
    private final PeEditDebounce breakDebounce = PeEditDebounce.forBreak();

    // Diagnostics: wall-clock of the last inbound edit, so the debug log can show the inter-arrival gap
    // (the tell for whether the 1.1.5 double-fire is a tight burst or spaced-out taps). -Djedrock.debug=pe.
    private long lastEditLogNanos;

    /** The player's currently selected hotbar slot (0-8), tracked from MobEquipment — the "held" item for
     *  the 1.1.5 click-transfer chest deposit. */
    private int heldSlot;

    /**
     * A right-click with an item (UseItem 0x23). If the clicked block is interactable (a chest), the core
     * uses it (opens the window) and we suppress the placement the same packet would otherwise be.
     */
    private void handleUseItem(ByteBuf pk) {
        PeBlockEditDecoder.UseItem use = PeBlockEditDecoder.decodeUseItemInteraction(pk);
        if (use == null || !loggedIn || listener == null) {
            return;
        }
        // 1.1.5 can't open a real chest window, so a chest right-click is click-transfer, not a window.
        // The core withdraws (plain) or deposits the held hotbar slot (sneaking); either way it's handled.
        if (listener.onChestInteract(this, use.x(), use.y(), use.z(), heldSlot)) {
            return; // a chest — click-transfer handled, no placement
        }
        PeBlockEditDecoder.BlockEdit placement = use.placement();
        if (placement == null) {
            return; // not a placeable block (e.g. a right-click with a tool) — nothing was drawn
        }
        long now = System.nanoTime();
        long gapMs = (now - lastEditLogNanos) / 1_000_000L;
        lastEditLogNanos = now;
        boolean applied = placeDebounce.accept(placement.x(), placement.y(), placement.z(), now);
        LOGGER.debug(() -> String.format(
                "[PE] place clicked=%d,%d,%d target=%d,%d,%d gap=%dms %s",
                use.x(), use.y(), use.z(), placement.x(), placement.y(), placement.z(),
                gapMs, applied ? "APPLIED" : "dropped"));
        if (applied) {
            applyEdit(placement);
        }
        // Either way, correct the client's optimistic view: a dropped shot is the "staircase" ghost block
        // the client already drew, and even an applied one leaves its next-cell prediction as a ghost. The
        // resync re-asserts the real neighbourhood so a single click leaves a single block. (1.1.5 only.)
        resyncAround(placement.x(), placement.y(), placement.z());
    }

    /**
     * MobEquipment (0x1f): the client changed its selected hotbar item. Track the hotbar slot (0-8) so the
     * click-transfer chest deposit knows which inventory slot the player is holding. Layout: entity runtime
     * id, the equipped item, inventorySlot (byte), hotbarSlot (byte), windowId (byte).
     *
     * <p>In <b>creative</b> the client owns its inventory (it never sends the server a survival-style slot
     * update), so we mirror the equipped item into the core inventory here — that's what lets a creative
     * chest deposit know what the player holds. Survival is server-authoritative, so we do NOT let the
     * client overwrite its slot there (the server already knows the contents from mining).
     */
    private void handleMobEquipment(ByteBuf pk) {
        try {
            ByteBufUtils.readVarLong(pk);          // entity runtime id
            McpeCodec.Item item = McpeCodec.readItem(pk); // equipped item
            pk.readUnsignedByte();                 // inventorySlot
            int hotbarSlot = pk.readUnsignedByte();
            // trailing windowId byte ignored
            if (hotbarSlot < 9) {
                heldSlot = hotbarSlot;
                LOGGER.debug(() -> "[PE] held hotbar slot = " + hotbarSlot);
                if (listener != null && listener.gameModeOf(this) == GameMode.CREATIVE) {
                    listener.onContainerSetSlot(this, WINDOW_ID_PLAYER, hotbarSlot, item.state(), item.count());
                }
                if (listener != null) {
                    // Redraw the hand on every other client's copy of this avatar.
                    listener.onHeldSlotChange(this, hotbarSlot);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse MobEquipment: " + e);
        }
    }

    /**
     * The client moved an item in a container and reports the new slot value (ContainerSetSlot 0x32,
     * inbound — Bedrock is client-authoritative here). windowId 0 = its own inventory; the chest's id =
     * the open chest. Hand it to the core to apply.
     */
    private void handleInboundContainerSetSlot(ByteBuf pk) {
        try {
            int windowId = pk.readUnsignedByte();
            int slot = ByteBufUtils.readSignedVarInt(pk);
            ByteBufUtils.readSignedVarInt(pk);           // hotbar slot — unused
            McpeCodec.Item item = McpeCodec.readItem(pk);
            // trailing selectSlot byte ignored
            if (loggedIn && listener != null && slot >= 0) {
                listener.onContainerSetSlot(this, windowId, slot, item.state(), item.count());
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse ContainerSetSlot: " + e);
        }
    }

    /** The client closed a container (ContainerClose 0x31). Clear its state and echo the close back. */
    private void handleInboundContainerClose(ByteBuf pk) {
        int windowId = pk.readUnsignedByte();
        if (loggedIn && listener != null) {
            listener.onWindowClose(this);
        }
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_CONTAINER_CLOSE);
            b.writeByte(windowId);
        });
    }

    /** The block a survival player is currently mining (from START_BREAK), broken on STOP_BREAK. */
    private long pendingBreak = NO_BREAK;
    private static final long NO_BREAK = Long.MIN_VALUE;

    /**
     * Dispatch a PlayerAction. Block breaking differs by mode: <b>creative</b> mines instantly, so a
     * START/CONTINUE break removes the block right away; <b>survival</b> takes time, so START only marks
     * the target and the block is removed on STOP_BREAK (mining finished) — otherwise every progress
     * packet would break the block on the first touch and hand out the item many times over.
     */
    private void handlePlayerAction(ByteBuf pk) {
        PeBlockEditDecoder.PlayerAction a = PeBlockEditDecoder.decodePlayerAction(pk);
        if (a == null || !loggedIn || listener == null) {
            return;
        }
        LOGGER.debug(() -> "[PE] PlayerAction action=" + a.action()
                + " @ " + a.x() + "," + a.y() + "," + a.z());
        boolean instantBreak = joinGameMode() == GameMode.CREATIVE;
        switch (a.action()) {
            case ACTION_START_BREAK -> {
                if (instantBreak) {
                    breakBlock(a.x(), a.y(), a.z());
                } else {
                    pendingBreak = packBlock(a.x(), a.y(), a.z()); // remember; break when mining finishes
                }
            }
            case ACTION_CONTINUE_BREAK -> {
                if (instantBreak) {
                    breakBlock(a.x(), a.y(), a.z());
                }
            }
            case ACTION_STOP_BREAK -> {
                if (!instantBreak && pendingBreak != NO_BREAK) {
                    int bx = unpackX(pendingBreak), by = unpackY(pendingBreak), bz = unpackZ(pendingBreak);
                    listener.onBlockChange(this, bx, by, bz, Blocks.AIR);
                    pendingBreak = NO_BREAK;
                    resyncAround(bx, by, bz); // erase any optimistic ghost on the neighbouring block
                }
            }
            case ACTION_ABORT_BREAK -> pendingBreak = NO_BREAK; // mining cancelled — leave the block
            case ACTION_START_SNEAK -> listener.onSneak(this, true);
            case ACTION_STOP_SNEAK -> listener.onSneak(this, false);
            case ACTION_START_SPRINT -> listener.onSprint(this, true);
            case ACTION_STOP_SPRINT -> listener.onSprint(this, false);
            case ACTION_RESPAWN -> handleRespawn();
            default -> { /* other actions (jump, glide…) not relayed yet */ }
        }
    }

    /**
     * The client clicked "Respawn" on its death screen. Unlike JE / 0.14 (whose health is server-driven,
     * so they never show a death screen for our silent respawn), the 1.1.5 client reports its own fatal
     * falls and shows a death screen locally — which SetHealth alone can't dismiss. Answer the button:
     * place the player back at spawn with a Respawn packet and refill the health bar, so the menu closes.
     * The core already healed and moved the player on death, so this just resyncs the client.
     */
    private void handleRespawn() {
        Location spawn = world.getSpawnLocation();
        // Respawn Y is eye-level (like MovePlayer / 0.14's Respawn), not feet like StartGame — sending
        // the feet Y spawned the player 1.62 blocks embedded in the ground.
        float eyeY = (float) spawn.y() + EYE_HEIGHT;
        sendGameBatch(b -> writeRespawn(b, (float) spawn.x(), eyeY, (float) spawn.z()));
        setHealth(20); // MAX_HEALTH — refill the bar the death screen emptied
    }

    /** Encode a Respawn body (protocol 113): the spawn position as three little-endian floats. */
    static void writeRespawn(ByteBuf b, float x, float y, float z) {
        ByteBufUtils.writeVarInt(b, ID_RESPAWN);
        b.writeFloatLE(x);
        b.writeFloatLE(y);
        b.writeFloatLE(z);
    }

    // Pack a block position into one long so a survival mine can remember its target across packets.
    private static long packBlock(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
    private static int unpackX(long v) { return (int) (v >> 38); }
    private static int unpackY(long v) { return (int) ((v >> 26) & 0xFFF); }
    private static int unpackZ(long v) { return (int) (v << 38 >> 38); }

    /**
     * The client reported a fall (its own physics decided the distance). Hand the distance to the core,
     * which turns it into fall damage — we never simulate gravity or poll positions for it.
     */
    private void handleEntityFall(ByteBuf pk) {
        try {
            ByteBufUtils.readVarLong(pk);        // entity runtime id (self)
            float fallDistance = pk.readFloatLE();
            // trailing bool ignored
            if (loggedIn && listener != null) {
                listener.onFall(this, fallDistance);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse EntityFall: " + e);
        }
    }

    /**
     * The client attacked or interacted with another entity (InteractPacket): {@code byte action} then
     * the target's runtime id (= the avatar's server entity id). A left-click is a melee attack — hand
     * the target to the core, which resolves the victim and applies damage. Wrapped so a malformed
     * packet can't take the session down.
     */
    private void handleInteract(ByteBuf pk) {
        try {
            int action = pk.readUnsignedByte();
            long target = ByteBufUtils.readVarLong(pk);   // entity runtime id
            if (action == INTERACT_LEFT_CLICK && loggedIn && listener != null) {
                listener.onAttack(this, target);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse Interact: " + e);
        }
    }

    /** Relay an inbound Animate (arm swing) to the core so other players see the swing. */
    private void handleInboundAnimate(ByteBuf pk) {
        try {
            int action = ByteBufUtils.readSignedVarInt(pk); // putVarInt (signed)
            if (action == ANIMATE_SWING_ARM && loggedIn && listener != null) {
                listener.onSwingArm(this);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse Animate: " + e);
        }
    }

    /**
     * Rebuild the slash command a Bedrock client parsed client-side and hand it to the core as a chat
     * line — {@code onChat} routes anything starting with {@code /} to the command system, so a PE
     * command takes exactly the same path a Java one does.
     */
    private void handleCommandStep(ByteBuf pk) {
        try {
            String line = McpeCommandStep.readCommandLine(pk);
            LOGGER.debug(() -> "[PE] CommandStep -> " + line);
            if (line != null && loggedIn && listener != null) {
                listener.onChat(this, line);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse CommandStep: " + e);
        }
    }

    /** Relay an inbound MCPE chat message to the core so it reaches every platform. */
    private void handleInboundText(ByteBuf pk) {
        try {
            int type = pk.readUnsignedByte();
            if (type == TEXT_TYPE_CHAT) {
                ByteBufUtils.readString(pk); // source name — we use the server-side name instead
            }
            String message = ByteBufUtils.readString(pk);
            if (loggedIn && listener != null && !message.isEmpty()) {
                listener.onChat(this, message);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse inbound Text: " + e);
        }
    }

    /**
     * Relay the client-authoritative MovePlayer to the core. MovePlayer y is the eye position, so
     * subtract the eye height to get the feet the core (and Java) work with.
     */
    private void handleInboundMove(ByteBuf pk) {
        try {
            ByteBufUtils.readVarLong(pk); // runtime id — the client's own, ignored
            float x = pk.readFloatLE();
            float y = pk.readFloatLE() - EYE_HEIGHT;
            float z = pk.readFloatLE();
            float pitch = pk.readFloatLE();
            pk.readFloatLE();             // head yaw
            float yaw = pk.readFloatLE();
            if (loggedIn && listener != null) {
                listener.onMove(this, x, y, z, yaw, pitch);
            }
            if (chunkView != null) {
                chunkView.recenter(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4, chunkSink);
            }
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse inbound MovePlayer: " + e);
        }
    }

    // ===== Join sequence =====

    /** Reply to Login: PlayStatus(success) + an empty ResourcePacksInfo. */
    private void sendLoginResponse() {
        sendGameBatch(
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
                    ByteBufUtils.writeIntBE(b, PLAY_STATUS_LOGIN_SUCCESS); // status is a big-endian int32
                },
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_RESOURCE_PACKS_INFO);
                    b.writeBoolean(false);   // must accept
                    b.writeShortLE(0);       // behaviour pack count
                    b.writeShortLE(0);       // resource pack count
                });
    }

    /** Reply to the resource-pack response with the world's StartGame. */
    private void sendStartGame() {
        Location spawn = world.getSpawnLocation();
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_START_GAME);

            // Player entity ids + gamemode
            ByteBufUtils.writeSignedVarLong(b, 1L);
            ByteBufUtils.writeVarLong(b, 1L);
            ByteBufUtils.writeSignedVarInt(b, joinGameMode().getId()); // join game mode (remembered / config)

            // Position + rotation
            b.writeFloatLE((float) spawn.x());
            b.writeFloatLE((float) spawn.y());
            b.writeFloatLE((float) spawn.z());
            b.writeFloatLE(0.0f);
            b.writeFloatLE(0.0f);

            // World generation basics
            ByteBufUtils.writeSignedVarInt(b, 12345);
            ByteBufUtils.writeSignedVarInt(b, 0);
            ByteBufUtils.writeSignedVarInt(b, 1);
            ByteBufUtils.writeSignedVarInt(b, 1);
            ByteBufUtils.writeSignedVarInt(b, 1);

            // World spawn block coords
            ByteBufUtils.writeSignedVarInt(b, spawn.getBlockX());
            ByteBufUtils.writeSignedVarInt(b, spawn.getBlockY());
            ByteBufUtils.writeSignedVarInt(b, spawn.getBlockZ());

            b.writeBoolean(true);
            ByteBufUtils.writeSignedVarInt(b, 0);
            b.writeBoolean(false);
            b.writeFloatLE(0.0f);
            b.writeFloatLE(0.0f);

            b.writeBoolean(true);
            b.writeBoolean(true);
            b.writeBoolean(false);

            b.writeBoolean(true);
            b.writeBoolean(false);

            ByteBufUtils.writeVarInt(b, 0);

            ByteBufUtils.writeString(b, "jedrock_level");
            ByteBufUtils.writeString(b, "Jedrock PE World");
            ByteBufUtils.writeString(b, "");

            b.writeBoolean(false);
            b.writeLongLE(0L);
        });
        // Only StartGame is sent here; the spawn PlayStatus is sent once, after the chunks.
    }

    /** Reply to the chunk-radius request: set the radius, stream the initial window, spawn. */
    private void sendWorld(int radius) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_CHUNK_RADIUS_UPDATED);
            ByteBufUtils.writeSignedVarInt(b, radius);
        });
        // Flight is only allowed outside survival (creative / spectator).
        sendAdventureSettings(joinGameMode().allowsFlight());

        // Starter (empty) hotbar before streaming chunks.
        sendInventory();

        // Stream the initial window around spawn
        Location spawn = world.getSpawnLocation();
        this.chunkView = new ChunkView(radius);
        chunkView.recenter(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, chunkSink);

        // Terrain is in; kick the client out of the load screen
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
            ByteBufUtils.writeIntBE(b, PLAY_STATUS_PLAYER_SPAWN);
        });

        // Movement-speed attribute — sent *after* PLAYER_SPAWN. A 1.1.5 client only applies attributes to
        // an already-spawned local player (PocketMine syncs them post-spawn); sent before the spawn status
        // it was silently dropped, leaving the client on its buggy default that accelerates without bound.
        sendAttributes();

        // Creative palette, once, right after the spawn status — the point PocketMine sends it, when
        // the client's inventory UI is up and will accept it.
        sendCreativeContent();

        // Commands, also post-spawn (where PocketMine sends them).
        sendCommandData();
    }

    /**
     * Turn the client's "/" input on and hand it the manifest of commands it may send.
     *
     * <p>Both are required. With commands disabled the client refuses the input outright ("cheats must
     * be enabled"); with them enabled but no manifest it silently drops any command it wasn't told
     * about. Only once both land does it parse the line and send back a {@code CommandStep} packet —
     * which {@link #handleCommandStep} turns back into a {@code "/…"} line for the core.
     */
    private void sendCommandData() {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_SET_COMMANDS_ENABLED);
            b.writeBoolean(true);
        });
        if (listener == null) {
            return;
        }
        var commands = listener.commands();
        if (commands.isEmpty()) {
            return; // nothing to advertise
        }
        String json = McpeAvailableCommands.buildJson(commands);
        LOGGER.debug(() -> "[PE] AvailableCommands: " + json);
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_AVAILABLE_COMMANDS);
            ByteBufUtils.writeString(b, json);
            ByteBufUtils.writeString(b, ""); // unused second JSON blob
        });
    }

    /**
     * Send AdventureSettings — mainly the flight permission. {@code allowFlight} rides the flags field;
     * the player entity id is an LE long (NOT a varint — a short write drops the whole packet, so flight
     * would never apply). OP command/permission level is kept so the creative menu and edits work.
     */
    private void sendAdventureSettings(boolean allowFlight) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_ADVENTURE_SETTINGS);
            ByteBufUtils.writeVarInt(b, allowFlight ? ADVENTURE_ALLOW_FLIGHT : 0);
            ByteBufUtils.writeVarInt(b, 2);           // command permission (OP)
            ByteBufUtils.writeVarInt(b, 0);           // action permissions
            ByteBufUtils.writeVarInt(b, 2);           // permission level (OP)
            ByteBufUtils.writeVarInt(b, 0);           // custom extension flags
            b.writeLongLE(1L);                        // player entity unique id (LE long)
        });
    }

    @Override
    public void setHealth(int health) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_SET_HEALTH);
            ByteBufUtils.writeSignedVarInt(b, health); // PMMP SetHealth uses a signed (zigzag) varint
        });
    }

    /** The mode this client joins in: its remembered choice this run, else the config default. */
    private GameMode joinGameMode() {
        return listener != null ? listener.gameModeFor(uuid) : properties.defaultGameMode();
    }

    @Override
    public void setGameMode(GameMode mode) {
        // SetPlayerGameType flips the HUD; AdventureSettings re-grants/revokes flight to match.
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_SET_PLAYER_GAME_TYPE);
            ByteBufUtils.writeSignedVarInt(b, mode.getId());
        });
        sendAdventureSettings(mode.allowsFlight());
    }

    @Override
    public void setInventorySlot(int slot, int state, int count) {
        // ContainerSetSlot (0x32): a single-slot update, which refreshes the hotbar HUD live (a full
        // ContainerSetContent updates the data but the client only shows it after the inventory opens).
        // Layout matches PMMP's ContainerSetSlotPacket exactly: windowId, slot, hotbarSlot (0), item,
        // selectSlot (0) — PMMP leaves hotbarSlot/selectSlot at their defaults, so we do too.
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_SLOT);
            b.writeByte(WINDOW_ID_PLAYER);            // window id (byte, not a varint here)
            ByteBufUtils.writeSignedVarInt(b, slot);  // inventory slot
            ByteBufUtils.writeSignedVarInt(b, 0);     // hotbarSlot (PMMP default)
            McpeCodec.writeSlot(b, state, count);
            b.writeByte(0);                           // selectSlot (PMMP default)
        });
    }

    /** The core inventory's storage slots (0-8 hotbar / 9-35 main) that map 1:1 onto PE window 0. */
    private static final int PE_PLAYER_SLOTS = 36;

    /**
     * The slot count PE 1.1.5 expects in the player window: {@code getSize() + getHotbarSize()} = 36 + 9 =
     * 45. PMMP appends 9 trailing air slots after the 36 storage slots, and the client won't wire up its
     * hotbar HUD unless the window has exactly this shape.
     */
    private static final int PE_PLAYER_WINDOW_SLOTS = 45;

    @Override
    public void setInventory(int[] states, int[] counts) {
        sendPlayerInventory(slot -> states[slot], slot -> counts[slot]);
    }

    /**
     * Send the player's own inventory (window 0) exactly as PMMP does at protocol 113, so the client
     * renders the hotbar HUD. Two details a plain ContainerSetContent misses — and the reason mined
     * items showed only with the inventory GUI open:
     * <ul>
     *   <li><b>45 slots, not 36.</b> PMMP sends {@code getSize() + getHotbarSize()} — the 36 storage
     *       slots (core 0-8 hotbar / 9-35 main, 1:1) followed by 9 trailing air slots.</li>
     *   <li><b>A 9-entry hotbar-link array.</b> Each on-screen hotbar position {@code i} maps to inventory
     *       slot {@code i}; PMMP writes the link as {@code index + getHotbarSize()} (= {@code i + 9}).
     *       Without these links the client fills storage but leaves the hotbar empty.</li>
     * </ul>
     * The core's armor / off-hand slots (36-40) live in separate PE windows (not modelled here yet).
     */
    private void sendPlayerInventory(java.util.function.IntUnaryOperator state,
                                     java.util.function.IntUnaryOperator count) {
        sendGameBatch(b -> writePlayerInventory(b, state, count));
    }

    /**
     * Encode the ContainerSetContent body for the player window (0), PMMP-exact so the client wires up its
     * hotbar HUD: window id, targetEid, 45 slots (36 storage then 9 air), then a 9-entry hotbar-link array
     * ({@code i + 9}). See {@link #sendPlayerInventory} for why the count and links matter.
     */
    static void writePlayerInventory(ByteBuf b, java.util.function.IntUnaryOperator state,
                                     java.util.function.IntUnaryOperator count) {
        ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_CONTENT);
        ByteBufUtils.writeVarInt(b, WINDOW_ID_PLAYER);
        ByteBufUtils.writeSignedVarLong(b, SELF_ENTITY_ID);
        ByteBufUtils.writeVarInt(b, PE_PLAYER_WINDOW_SLOTS);
        for (int slot = 0; slot < PE_PLAYER_WINDOW_SLOTS; slot++) {
            if (slot < PE_PLAYER_SLOTS) {
                McpeCodec.writeSlot(b, state.applyAsInt(slot), count.applyAsInt(slot));
            } else {
                McpeCodec.writeSlot(b, Blocks.AIR, 0); // 9 trailing hotbar-area slots are air
            }
        }
        ByteBufUtils.writeVarInt(b, 9);                       // hotbar-link count
        for (int i = 0; i < 9; i++) {
            ByteBufUtils.writeSignedVarInt(b, i + 9);         // hotbar pos i -> slot i (index + hotbarSize)
        }
    }

    @Override
    public void openContainer(int windowId, String title, int slots, int x, int y, int z) {
        // Chests are not openable on the retail 1.1.5 client yet. Two dead ends, both client-verified:
        // a block-bound ContainerOpen crashes it (it won't build a chest block-entity from our packets),
        // and an entity-bound (minecart-chest) container doesn't crash but raises no GUI. Stubbed to a
        // note until the wire is cracked; 0.14 and Java chests are unaffected. See the protocol memory.
        sendMessage("{gray}Сундуки на этой версии (1.1.5) пока недоступны.");
    }

    @Override
    public void setWindowItems(int windowId, int[] states, int[] counts) {
        // No-op: 1.1.5 chest opening is stubbed (see openContainer), so there is no window to fill.
    }

    /** Send the standard movement-speed attribute (0.1) to stop the PE client's runaway acceleration. */
    private void sendAttributes() {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_UPDATE_ATTRIBUTES);
            ByteBufUtils.writeVarLong(b, 1L);  // player runtime id
            ByteBufUtils.writeVarInt(b, 1);    // attribute count

            b.writeFloatLE(0.0f);              // min
            b.writeFloatLE(3.4028235E38f);     // max
            b.writeFloatLE(0.1f);              // current (vanilla walk speed)
            b.writeFloatLE(0.1f);              // default
            ByteBufUtils.writeString(b, "minecraft:movement");

            ByteBufUtils.writeVarInt(b, 0);    // modifier count
        });
    }

    /** The 1.1.5 creative menu — variant-rich blocks plus items (tools / armor / food / materials). */
    private static final int[] CREATIVE = PeCreativePalette.forV115();

    /**
     * The player's own entity id. StartGame assigns it 1, and the ContainerSetContent packets are
     * addressed to it (targetEid) exactly as PocketMine does for protocol 113.
     */
    private static final long SELF_ENTITY_ID = 1L;

    /**
     * Start with an empty player inventory, both modes. Creative fills its hands from the creative menu
     * (client-side, like vanilla), and survival fills it by mining (via {@code setInventory}). Sending a
     * starter hotbar here was the bug where "creative blocks" leaked into a survival inventory.
     */
    private void sendInventory() {
        sendPlayerInventory(slot -> Blocks.AIR, slot -> 0);
    }

    /**
     * Fill the creative menu (window {@link McpeProtocol#WINDOW_ID_CREATIVE}). Protocol 113 carries
     * the creative palette in a ContainerSetContent addressed to the creative window — an empty menu
     * just means it was never sent (or sent as the wrong 1.2+ InventoryContent packet).
     */
    private void sendCreativeContent() {
        sendContainerContent(WINDOW_ID_CREATIVE, CREATIVE.length, slot -> CREATIVE[slot], 1);
    }

    /**
     * Send a ContainerSetContent (0x34) for protocol 113: windowId, targetEid, slot count, the slots
     * themselves, then a hotbar-link count (0 — we don't remap the hotbar). Verified against PMMP.
     */
    private void sendContainerContent(int windowId, int slotCount, java.util.function.IntUnaryOperator slotState, int count) {
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_CONTENT);
            ByteBufUtils.writeVarInt(b, windowId);                 // window id (unsigned varint)
            ByteBufUtils.writeSignedVarLong(b, SELF_ENTITY_ID);    // targetEid (zigzag varlong)
            ByteBufUtils.writeVarInt(b, slotCount);                // slot count
            for (int slot = 0; slot < slotCount; slot++) {
                McpeCodec.writeSlot(b, slotState.applyAsInt(slot), count);
            }
            ByteBufUtils.writeVarInt(b, 0);                        // hotbar-link count (none)
        });
    }

    /**
     * Serialize and send one chunk column in its own game batch — one big batch of many full chunks
     * is fragile once split across RakNet fragments, so we keep each chunk small.
     */
    private void sendChunk(int chunkX, int chunkZ) {
        byte[] chunkData = McpeChunkSerializer.serialize(world, chunkX, chunkZ);
        LOGGER.debug(() -> "[PE] chunk (" + chunkX + "," + chunkZ + ") = " + chunkData.length + " bytes");
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, ID_FULL_CHUNK_DATA);
            ByteBufUtils.writeSignedVarInt(b, chunkX);
            ByteBufUtils.writeSignedVarInt(b, chunkZ);
            ByteBufUtils.writeVarInt(b, chunkData.length);
            b.writeBytes(chunkData);
        });
    }

    /** Send a single MCPE packet as its own 0xFE zlib batch. */
    private void sendGameBatch(Consumer<ByteBuf> packet) {
        sendGameBatch(packet, null);
    }

    /**
     * Per-thread batch + scratch buffers. An outbound packet is built and fully consumed (deflated)
     * within a single {@link #sendGameBatch} call before the method returns, so a thread can reuse the
     * same two heap buffers across every send instead of allocating a fresh pair each time — and a
     * given session is written to from several threads (its own RakNet thread plus other players'
     * threads relaying moves/chat), so keeping these per-thread rather than per-session avoids sharing.
     * Heap-backed and never released: they live for the thread's lifetime (bounded by the pool size).
     */
    private static final ThreadLocal<ByteBuf> BATCH_BUF = ThreadLocal.withInitial(Unpooled::buffer);
    private static final ThreadLocal<ByteBuf> SCRATCH_BUF = ThreadLocal.withInitial(Unpooled::buffer);

    /**
     * Wrap one or two MCPE packets in a zlib batch behind the 0xFE game-packet header and send them
     * reliably. Each packet is encoded into a reused {@code scratch} buffer purely to measure its
     * VarInt length prefix, then framed into the batch — so no per-packet {@code byte[]} is
     * allocated, and we deflate straight from the batch's backing array.
     */
    private void sendGameBatch(Consumer<ByteBuf> first, Consumer<ByteBuf> second) {
        ByteBuf batch = BATCH_BUF.get();
        ByteBuf scratch = SCRATCH_BUF.get();
        batch.clear();
        appendPacket(batch, scratch, first);
        if (second != null) {
            appendPacket(batch, scratch, second);
        }
        if (batch.readableBytes() == 0) {
            return; // every inner packet was cancelled by a tap — nothing to send
        }
        byte[] compressed = McpeCompression.deflate(
                batch.array(), batch.arrayOffset() + batch.readerIndex(), batch.readableBytes(), rawDeflate);

        // The outbound frame is handed to RakNet, which owns and releases it — so it can't be pooled here.
        ByteBuf out = Unpooled.buffer(1 + compressed.length);
        out.writeByte(GAME_PACKET_WRAPPER);
        out.writeBytes(compressed);
        session.send(out, RakNetReliability.RELIABLE_ORDERED);
    }

    /**
     * Encode one packet into {@code scratch}, offer it to any outbound tap, then (unless cancelled) frame it
     * (VarInt length + body) into {@code batch}. The tap peeks the leading VarInt id without consuming it.
     */
    private void appendPacket(ByteBuf batch, ByteBuf scratch, Consumer<ByteBuf> writer) {
        scratch.clear();
        writer.accept(scratch);
        if (listener != null && listener.hasPacketTaps()) {
            scratch.markReaderIndex();
            int id = ByteBufUtils.readVarInt(scratch);
            byte[] body = new byte[scratch.readableBytes()];
            scratch.getBytes(scratch.readerIndex(), body);
            scratch.resetReaderIndex();
            if (listener.onOutboundPacket(this, id, body)) {
                return; // cancelled — don't append it to the batch
            }
        }
        ByteBufUtils.writeVarInt(batch, scratch.readableBytes());
        batch.writeBytes(scratch);
    }

    @Override
    public void sendRawPacket(int packetId, byte[] payload) {
        // Frame as a normal game-batch packet ([VarInt id][payload]); it's tapped like any other send.
        sendGameBatch(b -> {
            ByteBufUtils.writeVarInt(b, packetId);
            if (payload != null) {
                b.writeBytes(payload);
            }
        });
    }
}
