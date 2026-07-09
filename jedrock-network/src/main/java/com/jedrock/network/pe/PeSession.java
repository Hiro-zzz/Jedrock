package com.jedrock.network.pe;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.network.ConnectionListener;
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
import java.util.Map;
import java.util.UUID;
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
    /** Shared uuid → skin map (owned by the PE server); lets us render other PE players' real skins. */
    private final Map<UUID, McpeSkin.Skin> skins;

    private volatile boolean loggedIn = false;
    private volatile UUID uuid;
    private volatile String username;
    /** This player's own skin (real one from the Login JWT, or synthetic); published to {@link #skins}. */
    private volatile McpeSkin.Skin skin;
    /** Compression mode observed on inbound batches; reused for outbound (chat etc.). */
    private volatile boolean rawDeflate = false;

    /** Chunk streaming state; created once the client's requested radius is known. */
    private ChunkView chunkView;
    private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
        @Override public void load(int cx, int cz) { sendChunk(cx, cz); }
        @Override public void unload(int cx, int cz) { /* PE 1.1.5 client culls by distance */ }
    };

    PeSession(RakNetServerSession session, ConnectionListener listener, ProtocolVersion protocol,
              World world, Map<UUID, McpeSkin.Skin> skins) {
        this.session = session;
        this.listener = listener;
        this.protocol = protocol;
        this.world = world;
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
    public void sendBlockChange(int x, int y, int z, int state) {
        // Typed UpdateBlock (0x16): one block instead of re-sending the whole affected chunk. The
        // client applies it if it has the chunk loaded and ignores it otherwise. Block position is
        // x/z zigzag-varint and y unsigned-varint (same layout the inbound edit decoder reads); the
        // canonical state splits into a legacy id and 4-bit meta, and the flags request a
        // neighbour-aware re-render.
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
    public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        sendGameBatch(b -> writeSetEntityDataFlags(b, entityId, sneaking, sprinting, usingItem));
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
            case ID_MOVE_PLAYER -> handleInboundMove(pk);
            case ID_INVENTORY_TRANSACTION -> applyEdit(PeBlockEditDecoder.decodeInventoryTransaction(pk));
            case ID_USE_ITEM -> applyEdit(PeBlockEditDecoder.decodeUseItem(pk));
            case ID_PLAYER_ACTION -> handlePlayerAction(pk);
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

    /** Dispatch a PlayerAction: an instant creative break, or a sneak-pose toggle. */
    private void handlePlayerAction(ByteBuf pk) {
        PeBlockEditDecoder.PlayerAction a = PeBlockEditDecoder.decodePlayerAction(pk);
        if (a == null || !loggedIn || listener == null) {
            return;
        }
        switch (a.action()) {
            case ACTION_START_BREAK, ACTION_CONTINUE_BREAK ->
                    listener.onBlockChange(this, a.x(), a.y(), a.z(), Blocks.AIR);
            case ACTION_START_SNEAK -> listener.onSneak(this, true);
            case ACTION_STOP_SNEAK -> listener.onSneak(this, false);
            case ACTION_START_SPRINT -> listener.onSprint(this, true);
            case ACTION_STOP_SPRINT -> listener.onSprint(this, false);
            default -> { /* other actions (jump, glide…) not relayed yet */ }
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
            ByteBufUtils.writeSignedVarInt(b, 1);   // creative

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
        sendGameBatch(
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_CHUNK_RADIUS_UPDATED);
                    ByteBufUtils.writeSignedVarInt(b, radius);
                },
                b -> {
                    ByteBufUtils.writeVarInt(b, ID_ADVENTURE_SETTINGS);
                    ByteBufUtils.writeVarInt(b, ADVENTURE_ALLOW_FLIGHT); // let the client toggle flight
                    ByteBufUtils.writeVarInt(b, 2);           // command permission (OP)
                    ByteBufUtils.writeVarInt(b, 0);           // action permissions
                    ByteBufUtils.writeVarInt(b, 2);           // permission level (OP)
                    ByteBufUtils.writeVarInt(b, 0);           // custom extension flags
                    b.writeLongLE(1L);                        // player entity unique id — an LE long,
                                                              // NOT a varint (a short write here drops
                                                              // the whole packet, so flight never applied)
                });

        // Movement-speed attribute + starter hotbar before streaming chunks.
        sendAttributes();
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

        // Creative palette, once, right after the spawn status — the point PocketMine sends it, when
        // the client's inventory UI is up and will accept it.
        sendCreativeContent();
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

    /** Placeable blocks handed to a joining Bedrock player's hotbar (creative). */
    private static final int[] HOTBAR = {
            Blocks.STONE, Blocks.DIRT, Blocks.GRASS, Blocks.COBBLESTONE,
            Blocks.PLANKS, Blocks.SAND, Blocks.LOG, Blocks.GLASS,
    };

    /** The creative menu's block palette — variant-rich, shared with 0.14 ({@link PeCreativePalette}). */
    private static final int[] CREATIVE = PeCreativePalette.states();

    /**
     * The player's own entity id. StartGame assigns it 1, and the ContainerSetContent packets are
     * addressed to it (targetEid) exactly as PocketMine does for protocol 113.
     */
    private static final long SELF_ENTITY_ID = 1L;

    /** Populate the player inventory: the first slots (hotbar) with placeable blocks, the rest empty. */
    private void sendInventory() {
        sendContainerContent(WINDOW_ID_PLAYER, 36,
                slot -> slot < HOTBAR.length ? Blocks.state(HOTBAR[slot], 0) : Blocks.AIR, 64);
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
     * Wrap one or two MCPE packets in a zlib batch behind the 0xFE game-packet header and send them
     * reliably. Each packet is encoded into a reused {@code scratch} buffer purely to measure its
     * VarInt length prefix, then framed into the batch — so no per-packet {@code byte[]} is
     * allocated, and we deflate straight from the batch's backing array.
     */
    private void sendGameBatch(Consumer<ByteBuf> first, Consumer<ByteBuf> second) {
        ByteBuf batch = Unpooled.buffer();
        ByteBuf scratch = Unpooled.buffer();
        try {
            appendPacket(batch, scratch, first);
            if (second != null) {
                appendPacket(batch, scratch, second);
            }
            byte[] compressed = McpeCompression.deflate(
                    batch.array(), batch.arrayOffset() + batch.readerIndex(), batch.readableBytes(), rawDeflate);

            ByteBuf out = Unpooled.buffer(1 + compressed.length);
            out.writeByte(GAME_PACKET_WRAPPER);
            out.writeBytes(compressed);
            session.send(out, RakNetReliability.RELIABLE_ORDERED);
        } finally {
            scratch.release();
            batch.release();
        }
    }

    /** Encode one packet into {@code scratch}, then frame it (VarInt length + body) into {@code batch}. */
    private static void appendPacket(ByteBuf batch, ByteBuf scratch, Consumer<ByteBuf> writer) {
        scratch.clear();
        writer.accept(scratch);
        ByteBufUtils.writeVarInt(batch, scratch.readableBytes());
        batch.writeBytes(scratch);
    }
}
