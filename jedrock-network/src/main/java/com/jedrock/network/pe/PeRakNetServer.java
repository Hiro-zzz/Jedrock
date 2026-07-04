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
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerListener;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetSessionListener;
import com.nukkitx.network.raknet.RakNetState;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock (PE 1.1.5) server built on the nukkitx RakNet transport.
 *
 * <p>The library owns the RakNet layer (offline handshake, datagram framing, reliability,
 * ACK/NAK, split reassembly). On top of it we implement just enough of the MCPE 1.1.5
 * (protocol 113) game layer to get a client into a flat, illusory world:
 *
 * <pre>
 *   Login (0x01)                -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo
 *   ResourcePackResponse (0x08) -> StartGame + PlayStatus(PLAYER_SPAWN)
 *   RequestChunkRadius (0x45)   -> ChunkRadiusUpdated + NetworkChunkPublisherUpdate
 *                                  + AdventureSettings + 3x3 empty chunks + PlayStatus(PLAYER_SPAWN)
 * </pre>
 *
 * <p>Game packets travel as a {@code 0xFE} wrapper around a zlib-compressed batch of
 * VarInt-length-prefixed MCPE packets.
 */
public final class PeRakNetServer {

    private static final JLogger LOGGER = JLogger.getLogger(PeRakNetServer.class);

    /**
     * RakNet protocol version MCPE 1.1.5 sends in Open Connection Request 1 — confirmed to be 8.
     * Overridable via {@code -Djedrock.pe.raknetProtocolVersion=N} for other client builds.
     */
    public static final int RAKNET_PROTOCOL_VERSION =
            Integer.getInteger("jedrock.pe.raknetProtocolVersion", 8);

    // --- MCPE 1.1.5 (protocol 113) packet ids ---
    private static final int GAME_PACKET_WRAPPER = 0xFE;
    private static final int ID_LOGIN = 0x01;
    private static final int ID_PLAY_STATUS = 0x02;
    private static final int ID_TEXT = 0x09;
    private static final int ID_RESOURCE_PACKS_INFO = 0x06;
    private static final int ID_RESOURCE_PACK_RESPONSE = 0x08;
    private static final int ID_START_GAME = 0x0B;
    private static final int ID_ADD_PLAYER = 0x0C;
    private static final int ID_REMOVE_ENTITY = 0x0E;
    private static final int ID_MOVE_PLAYER = 0x13;
    private static final int ID_INVENTORY_TRANSACTION = 0x1E;
    private static final int ID_USE_ITEM = 0x23;  // Win10 1.1.5 carries block placement here
    private static final int ID_PLAYER_ACTION = 0x24;
    private static final int ID_ADVENTURE_SETTINGS = 0x37;
    private static final int ID_PLAYER_LIST = 0x3F;

    // PlayerAction action ids (protocol 113). In creative the client reports a break with
    // CONTINUE_BREAK carrying the block position, so we break on that.
    private static final int ACTION_START_BREAK = 0;
    private static final int ACTION_CONTINUE_BREAK = 18;

    // InventoryTransaction: transaction types + UseItem action types + inventory-action sources.
    private static final int TRANSACTION_USE_ITEM = 2;
    private static final int USE_ITEM_CLICK_BLOCK = 0; // place
    private static final int USE_ITEM_BREAK_BLOCK = 2; // break
    private static final int SOURCE_CONTAINER = 0;
    private static final int SOURCE_WORLD = 2;
    private static final int SOURCE_CREATIVE = 3;
    private static final int SOURCE_TODO = 99999;

    /** Block-face offsets (Bedrock uses the same order as Java): down,up,north,south,west,east. */
    private static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    private static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};
    private static final int ID_FULL_CHUNK_DATA = 0x3A;
    private static final int ID_REQUEST_CHUNK_RADIUS = 0x45;
    private static final int ID_CHUNK_RADIUS_UPDATED = 0x46;

    private static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    private static final int PLAY_STATUS_PLAYER_SPAWN = 3;

    // MCPE TextPacket types
    private static final int TEXT_TYPE_RAW = 0;
    private static final int TEXT_TYPE_CHAT = 1;

    // MCPE PlayerList actions
    private static final int PLAYER_LIST_ADD = 0;
    private static final int PLAYER_LIST_REMOVE = 1;

    // MCPE movement + inventory init
    private static final int ID_UPDATE_ATTRIBUTES = 0x1D; // movement-speed fix
    private static final int ID_INVENTORY_CONTENT = 0x31; // creative hotbar init

    /**
     * MCPE MovePlayer carries the <em>eye</em> position (feet + 1.62), while AddPlayer and StartGame
     * use feet. Confirmed by a standing client reporting y=59.62 while its feet were on a block top
     * at 58 (fractional .62 = the eye offset). The core works in feet, so convert on MovePlayer only.
     */
    private static final float EYE_HEIGHT = 1.62f;

    /** Max chunk view radius we honour from the client's RequestChunkRadius (join cost vs. distance). */
    private static final int MAX_VIEW_RADIUS = 4;

    private final InetSocketAddress address;
    private final ProtocolVersion protocol;
    private final ConnectionListener listener;
    private final World world;

    private RakNetServer server;

    public PeRakNetServer(InetSocketAddress address, ProtocolVersion protocol, ConnectionListener listener, World world) {
        this.address = address;
        this.protocol = protocol;
        this.listener = listener;
        this.world = world;
    }

    public void bind() {
        RakNetServer raknet = new RakNetServer(address);
        raknet.setProtocolVersion(RAKNET_PROTOCOL_VERSION);
        raknet.setListener(new Listener(raknet));
        raknet.bind().join(); // throws (via CompletionException) if the bind fails
        this.server = raknet;

        LOGGER.info("Listening on " + address + " for " + protocol.getVersionName()
                + " (Bedrock/RakNet, protocol v" + RAKNET_PROTOCOL_VERSION + ")");
    }

    public void close() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /** Server-level RakNet callbacks (ping / accept / session creation). */
    private final class Listener implements RakNetServerListener {

        private final RakNetServer raknet;

        Listener(RakNetServer raknet) {
            this.raknet = raknet;
        }

        @Override
        public boolean onConnectionRequest(InetSocketAddress address) {
            return true; // accept everyone for now
        }

        @Override
        public byte[] onQuery(InetSocketAddress address) {
            LOGGER.debug(() -> "[PE] ping from " + address);
            // MCPE server-list advertisement (semicolon-separated).
            String motd = String.join(";",
                    "MCPE",
                    "Jedrock",                                      // MOTD line 1
                    Integer.toString(protocol.getProtocolNumber()), // MCPE protocol (113 for 1.1.5)
                    protocol.getVersionName(),                      // "1.1.5"
                    "0",                                            // online players
                    "10",                                           // max players
                    Long.toString(raknet.getGuid()),                // server GUID
                    "Jedrock PE",                                   // MOTD line 2
                    "Survival",                                     // game mode
                    "1",                                            // game mode (numeric)
                    Integer.toString(address.getPort()),            // IPv4 port
                    Integer.toString(address.getPort())             // IPv6 port
            ) + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            LOGGER.info("[PE] session from " + session.getAddress()
                    + " (mtu=" + session.getMtu() + ", raknet v" + session.getProtocolVersion() + ")");
            session.setListener(new SessionHandler(session, listener, protocol, world));
        }

        @Override
        public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf buf = packet.content();
            if (buf.isReadable()) {
                LOGGER.debug(() -> "[PE] unhandled datagram from " + packet.sender()
                        + " id=0x" + Integer.toHexString(buf.getUnsignedByte(buf.readerIndex())));
            }
        }
    }

    /**
     * Per-session callbacks (state changes + inbound MCPE batch) AND the {@link PlayerConnection}
     * the core sees — so a Bedrock player lands in the same PlayerRegistry as a Java one.
     */
    private static final class SessionHandler implements RakNetSessionListener, PlayerConnection {

        private final RakNetServerSession session;
        private final ConnectionListener listener;
        private final ProtocolVersion protocol;
        private final World world;

        private volatile boolean loggedIn = false;
        private volatile UUID uuid;
        private volatile String username;
        /** Compression mode observed on inbound batches; reused for outbound (chat etc.). */
        private volatile boolean rawDeflate = false;

        /** Chunk streaming state; created once the client's requested radius is known. */
        private ChunkView chunkView;
        private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
            @Override public void load(int cx, int cz) { sendChunk(cx, cz); }
            @Override public void unload(int cx, int cz) { /* PE 1.1.5 client culls by distance */ }
        };

        SessionHandler(RakNetServerSession session, ConnectionListener listener, ProtocolVersion protocol, World world) {
            this.session = session;
            this.listener = listener;
            this.protocol = protocol;
            this.world = world;
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
            byte[] text = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_TEXT);
                b.writeByte(TEXT_TYPE_RAW);
                ByteBufUtils.writeString(b, message);
            });
            sendGameBatch(text);
        }

        @Override
        public void addToTab(UUID uuid, String name) {
            // The PE pause-menu list is fed by showPlayer's PlayerList entry (it needs an
            // entity id + skin, which this signature doesn't carry). No separate tab packet.
        }

        @Override
        public void removeFromTab(UUID uuid) {
            // See addToTab; hidePlayer removes the PlayerList entry.
        }

        @Override
        public void showPlayer(UUID uuid, String name, long entityId,
                               double x, double y, double z, float yaw, float pitch) {
            // PlayerList ADD must precede AddPlayer — it carries the skin the avatar renders with.
            byte[] playerList = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
                b.writeByte(PLAYER_LIST_ADD);
                ByteBufUtils.writeVarInt(b, 1);                    // entry count
                writeUuid(b, uuid);
                ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
                ByteBufUtils.writeString(b, name);
                ByteBufUtils.writeString(b, "Standard_Custom");    // skin model
                ByteBufUtils.writeByteArray(b, syntheticSkin(uuid));
            });
            byte[] addPlayer = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_ADD_PLAYER);
                writeUuid(b, uuid);
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
                ByteBufUtils.writeVarInt(b, 0);                    // entity metadata: empty
            });
            sendGameBatch(playerList, addPlayer);
        }

        @Override
        public void hidePlayer(UUID uuid, long entityId) {
            byte[] removeEntity = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_REMOVE_ENTITY);
                ByteBufUtils.writeSignedVarLong(b, entityId);
            });
            byte[] playerListRemove = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
                b.writeByte(PLAYER_LIST_REMOVE);
                ByteBufUtils.writeVarInt(b, 1);
                writeUuid(b, uuid);
            });
            sendGameBatch(removeEntity, playerListRemove);
        }

        @Override
        public void sendBlockChange(int x, int y, int z, int blockId) {
            // Until a typed UpdateBlock is verified for protocol 113, reflect edits by re-sending
            // the affected chunk (the world already holds the new block). Heavier than a single
            // block packet, but reuses the proven chunk path. Only if the client has that chunk.
            if (chunkView != null) {
                sendChunk(x >> 4, z >> 4);
            }
        }

        @Override
        public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
            byte[] move = buildPacket(b -> {
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
            sendGameBatch(move);
        }

        /** MCPE UUIDs travel as two little-endian longs (msb, lsb). */
        private static void writeUuid(ByteBuf b, UUID uuid) {
            b.writeLongLE(uuid.getMostSignificantBits());
            b.writeLongLE(uuid.getLeastSignificantBits());
        }

        /** A small palette of clearly distinct colours so avatars are easy to tell apart. */
        private static final int[] SKIN_PALETTE = {
                0xE64A3B, // red
                0x3B82E6, // blue
                0x2EA044, // green
                0xE6B02E, // amber
                0x8E44C4, // purple
                0x16A0A0, // teal
                0xE66AB0, // pink
                0xE67E22, // orange
                0x7FB31B, // lime
                0x5D6D7E, // slate
                0xC0392B, // crimson
                0x2C3E50, // navy
        };

        /**
         * MCPE 1.1 has no signed-skin requirement, but the data must be a valid RGBA
         * texture (64x32 = 8192 bytes). Until real skins are relayed, build a simple
         * two-tone humanoid placeholder: a per-player palette colour on the body and a
         * lighter shade on the head, so remote players are distinct and read as characters
         * rather than flat blobs. Head occupies the top 16 rows of a standard 64x32 skin.
         */
        private static byte[] syntheticSkin(UUID uuid) {
            int rgb = SKIN_PALETTE[Math.floorMod(uuid.hashCode(), SKIN_PALETTE.length)];
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            byte[] data = new byte[64 * 32 * 4];
            for (int y = 0; y < 32; y++) {
                boolean head = y < 16;
                int pr = head ? lighten(r) : r;
                int pg = head ? lighten(g) : g;
                int pb = head ? lighten(b) : b;
                for (int x = 0; x < 64; x++) {
                    int i = (y * 64 + x) * 4;
                    data[i] = (byte) pr;
                    data[i + 1] = (byte) pg;
                    data[i + 2] = (byte) pb;
                    data[i + 3] = (byte) 0xFF;
                }
            }
            return data;
        }

        /** Shift a channel ~40% toward white — used to tint the head lighter than the body. */
        private static int lighten(int c) {
            return c + (255 - c) * 2 / 5;
        }

        /** Send the standard movement-speed attribute (0.1) to stop the PE client's runaway acceleration. */
        private void sendAttributes() {
            byte[] attributesPacket = buildPacket(b -> {
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
            sendGameBatch(attributesPacket);
        }

        /** Placeable blocks handed to a joining Bedrock player's hotbar (creative). */
        private static final int[] HOTBAR = {
                Blocks.STONE, Blocks.DIRT, Blocks.GRASS, Blocks.COBBLESTONE,
                Blocks.PLANKS, Blocks.SAND, Blocks.LOG, Blocks.GLASS,
        };

        /** Populate the player inventory: the first slots (hotbar) with placeable blocks, the rest empty. */
        private void sendInventory() {
            byte[] inventoryPacket = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_INVENTORY_CONTENT);
                ByteBufUtils.writeVarInt(b, 0);    // container id 0 = player inventory
                ByteBufUtils.writeVarInt(b, 36);   // 36 slots (inventory + hotbar)
                for (int slot = 0; slot < 36; slot++) {
                    writeSlot(b, slot < HOTBAR.length ? HOTBAR[slot] : Blocks.AIR, 64);
                }
            });
            sendGameBatch(inventoryPacket);
        }

        /** Write one network Item slot (id, aux = meta&lt;&lt;8 | count, no NBT / can-place / can-destroy). */
        private static void writeSlot(ByteBuf b, int id, int count) {
            ByteBufUtils.writeSignedVarInt(b, id);
            if (id == Blocks.AIR) {
                return; // air carries no further fields
            }
            ByteBufUtils.writeSignedVarInt(b, count & 0xFF); // meta 0, count in the low byte
            b.writeShortLE(0);                               // NBT length
            ByteBufUtils.writeVarInt(b, 0);                  // can place on: none
            ByteBufUtils.writeVarInt(b, 0);                  // can destroy: none
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
                while (batch.isReadable()) {
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
                    Identity identity = extractIdentity(pk);
                    this.uuid = identity.uuid();
                    this.username = identity.name();
                    LOGGER.info("[PE] Login: " + username + " (" + uuid + ") → PlayStatus + ResourcePacksInfo");
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
                case ID_INVENTORY_TRANSACTION -> handleInventoryTransaction(pk);
                case ID_USE_ITEM -> handleUseItemPlace(pk);
                case ID_PLAYER_ACTION -> handlePlayerAction(pk);
                default -> { /* other gameplay packet — nothing to answer yet */ }
            }
        }

        /** Register the fully-joined player in the core, exactly once. */
        private void registerPlayer() {
            if (loggedIn || uuid == null) return;
            loggedIn = true;
            if (listener != null) {
                listener.onLogin(this, uuid, username);
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
         * Relay the client-authoritative MovePlayer to the core. MovePlayer y is the eye
         * position, so subtract the eye height to get the feet the core (and Java) work with.
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

        /**
         * Decode the Win10 1.1.5 block-placement packet (0x23). Layout, decoded from captured
         * bytes: block position (x svarint, y uvarint, z svarint), clicked face (svarint), hotbar
         * slot (svarint), player + click Vector3f (6 floats) and one flag byte, then the held Item.
         * The new block goes at the clicked block offset by the face. A paired (0,0,0) packet is a
         * sync echo and is filtered out.
         */
        private void handleUseItemPlace(ByteBuf pk) {
            try {
                int bx = ByteBufUtils.readSignedVarInt(pk);
                int by = ByteBufUtils.readVarInt(pk);   // block y is unsigned
                int bz = ByteBufUtils.readSignedVarInt(pk);
                int face = ByteBufUtils.readSignedVarInt(pk);
                ByteBufUtils.readSignedVarInt(pk);      // hotbar slot — unused
                pk.skipBytes(25);                       // player + click Vector3f (24) + 1 flag
                int itemId = readSlotItemId(pk);        // block in hand

                boolean atOrigin = bx == 0 && by == 0 && bz == 0;
                if (!atOrigin && Blocks.isKnown(itemId) && itemId != Blocks.AIR
                        && loggedIn && listener != null) {
                    int f = (face >= 0 && face < 6) ? face : 1;
                    listener.onBlockChange(this, bx + FACE_DX[f], by + FACE_DY[f], bz + FACE_DZ[f], itemId);
                }
            } catch (RuntimeException e) {
                LOGGER.debug(() -> "[PE] could not parse UseItem place: " + e);
            }
        }

        /**
         * Decode a Bedrock PlayerAction (protocol 113): entity runtime id, action, block position,
         * face. In creative, breaking a block is instant, so a start/stop-break action on a block
         * means "remove it". This is how the 1.1.5 Win10 client reports breaks (not via transaction).
         */
        private void handlePlayerAction(ByteBuf pk) {
            try {
                ByteBufUtils.readVarLong(pk);          // entity runtime id (unsigned)
                int action = ByteBufUtils.readSignedVarInt(pk);
                int bx = ByteBufUtils.readSignedVarInt(pk);
                int by = ByteBufUtils.readVarInt(pk);  // block y is unsigned
                int bz = ByteBufUtils.readSignedVarInt(pk);
                ByteBufUtils.readSignedVarInt(pk);     // face — unused
                // Creative: break instantly on the action that carries the block position.
                boolean isBreak = action == ACTION_START_BREAK || action == ACTION_CONTINUE_BREAK;
                if (isBreak && loggedIn && listener != null) {
                    listener.onBlockChange(this, bx, by, bz, Blocks.AIR);
                }
            } catch (RuntimeException e) {
                LOGGER.debug(() -> "[PE] could not parse PlayerAction: " + e);
            }
        }

        /**
         * Decode a Bedrock InventoryTransaction (protocol 113 layout) and, if it is a block
         * place or break, relay it to the core. The packet crams several concepts together:
         * a transaction type, a list of inventory actions (each with two nested items), then the
         * UseItem data (action type, block position, face, held item, positions). We must parse the
         * item format exactly to skip the actions and reach the UseItem block coordinates.
         */
        private void handleInventoryTransaction(ByteBuf pk) {
            try {
                int transactionType = ByteBufUtils.readVarInt(pk); // unsigned
                int actionCount = ByteBufUtils.readVarInt(pk);     // unsigned
                for (int i = 0; i < actionCount; i++) {
                    if (!skipInventoryAction(pk)) {
                        return; // unknown source type — can't skip safely, bail before misaligning
                    }
                }
                if (transactionType != TRANSACTION_USE_ITEM) {
                    return; // normal/mismatch/use-on-entity/release — not a world edit
                }

                int actionType = ByteBufUtils.readVarInt(pk);   // unsigned
                int bx = ByteBufUtils.readSignedVarInt(pk);
                int by = ByteBufUtils.readVarInt(pk);           // block y is an unsigned varint
                int bz = ByteBufUtils.readSignedVarInt(pk);
                int face = ByteBufUtils.readSignedVarInt(pk);
                ByteBufUtils.readSignedVarInt(pk);              // hotbar slot — unused
                int itemId = readSlotItemId(pk);               // block in hand (for placement)
                // Remaining player/click Vector3f are not needed.

                if (!loggedIn || listener == null) return;

                if (actionType == USE_ITEM_BREAK_BLOCK) {
                    listener.onBlockChange(this, bx, by, bz, Blocks.AIR);
                } else if (actionType == USE_ITEM_CLICK_BLOCK && Blocks.isKnown(itemId) && itemId != Blocks.AIR) {
                    int f = (face >= 0 && face < 6) ? face : 1;
                    listener.onBlockChange(this, bx + FACE_DX[f], by + FACE_DY[f], bz + FACE_DZ[f], itemId);
                }
            } catch (RuntimeException e) {
                LOGGER.debug(() -> "[PE] could not parse InventoryTransaction: " + e);
            }
        }

        /**
         * Parse (to skip) one NetworkInventoryAction. Returns false if the source type is unknown,
         * in which case the caller must stop — we can no longer track the read position.
         */
        private boolean skipInventoryAction(ByteBuf pk) {
            int sourceType = ByteBufUtils.readVarInt(pk); // unsigned
            switch (sourceType) {
                case SOURCE_CONTAINER, SOURCE_TODO -> ByteBufUtils.readSignedVarInt(pk); // windowId
                case SOURCE_WORLD -> ByteBufUtils.readVarInt(pk);                        // source flags
                case SOURCE_CREATIVE -> { /* no extra field */ }
                default -> {
                    LOGGER.debug(() -> "[PE] unknown inventory action source " + sourceType);
                    return false;
                }
            }
            ByteBufUtils.readVarInt(pk); // inventory slot (unsigned)
            readSlotItemId(pk);          // old item
            readSlotItemId(pk);          // new item
            return true;
        }

        /**
         * Read one network Item (protocol 113) and return its id (0 = air). Consumes the whole
         * item so the read position stays aligned: id, aux (meta/count), optional NBT, and the
         * can-place-on / can-destroy string lists.
         */
        private static int readSlotItemId(ByteBuf pk) {
            int id = ByteBufUtils.readSignedVarInt(pk);
            if (id == 0) {
                return 0; // air — no further fields
            }
            ByteBufUtils.readSignedVarInt(pk);        // aux value (meta << 8 | count)
            int nbtLen = pk.readShortLE() & 0xFFFF;   // NBT length (little-endian)
            if (nbtLen > 0) {
                pk.skipBytes(nbtLen);
            }
            int canPlaceOn = ByteBufUtils.readVarInt(pk);
            for (int i = 0; i < canPlaceOn; i++) ByteBufUtils.readString(pk);
            int canDestroy = ByteBufUtils.readVarInt(pk);
            for (int i = 0; i < canDestroy; i++) ByteBufUtils.readString(pk);
            return id;
        }

        /** Reply to Login: PlayStatus(success) + an empty ResourcePacksInfo. */
        private void sendLoginResponse() {
            byte[] playStatus = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
                ByteBufUtils.writeIntBE(b, PLAY_STATUS_LOGIN_SUCCESS); // status is a big-endian int32
            });
            byte[] resourcePacksInfo = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_RESOURCE_PACKS_INFO);
                b.writeBoolean(false);   // must accept
                b.writeShortLE(0);       // behaviour pack count
                b.writeShortLE(0);       // resource pack count
            });
            sendGameBatch(playStatus, resourcePacksInfo);
        }

        /** Reply to the resource-pack response with the world's StartGame. */
        private void sendStartGame() {
            Location spawn = world.getSpawnLocation();
            byte[] startGame = buildPacket(b -> {
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

            // Send only StartGame here; the spawn PlayStatus is sent once, after the chunks.
            sendGameBatch(startGame);
        }

        /** Reply to the chunk-radius request: set the radius, stream the initial window, spawn. */
        private void sendWorld(int radius) {
            sendGameBatch(
                    buildPacket(b -> {
                        ByteBufUtils.writeVarInt(b, ID_CHUNK_RADIUS_UPDATED);
                        ByteBufUtils.writeSignedVarInt(b, radius);
                    }),
                    buildPacket(b -> {
                        ByteBufUtils.writeVarInt(b, ID_ADVENTURE_SETTINGS);
                        ByteBufUtils.writeVarInt(b, 0x08 | 0x40); // flags: allow flight | world builder
                        ByteBufUtils.writeVarInt(b, 2);           // command permission (OP)
                        ByteBufUtils.writeVarInt(b, 0);           // action permissions
                        ByteBufUtils.writeVarInt(b, 2);           // permission level (OP)
                        ByteBufUtils.writeVarInt(b, 0);           // custom extension flags
                        ByteBufUtils.writeVarLong(b, 1L);         // player entity unique id
                    })
            );

            // Movement-speed + hotbar fixes before streaming chunks.
            sendAttributes();
            sendInventory();

            // Stream the initial window around spawn
            Location spawn = world.getSpawnLocation();
            this.chunkView = new ChunkView(radius);
            chunkView.recenter(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, chunkSink);

            // Terrain is in; kick the client out of the load screen
            sendGameBatch(playStatus());
        }

        /**
         * Serialize and send one chunk column in its own game batch — one big batch of many full
         * chunks is fragile once split across RakNet fragments, so we keep each chunk small.
         */
        private void sendChunk(int chunkX, int chunkZ) {
            byte[] chunkData = buildChunkPayload(chunkX, chunkZ);
            LOGGER.debug(() -> "[PE] chunk (" + chunkX + "," + chunkZ + ") = " + chunkData.length + " bytes");
            sendGameBatch(buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_FULL_CHUNK_DATA);
                ByteBufUtils.writeSignedVarInt(b, chunkX);
                ByteBufUtils.writeSignedVarInt(b, chunkZ);
                ByteBufUtils.writeVarInt(b, chunkData.length);
                b.writeBytes(chunkData);
            }));
        }

        /** 2048 nibble-bytes of full light (0xF per cell) — a lit sub-chunk so the world isn't dark. */
        private static final byte[] FULL_LIGHT = new byte[2048];
        static {
            Arrays.fill(FULL_LIGHT, (byte) 0xFF);
        }

        /**
         * Serialize a chunk column payload in the MCPE 1.0/1.1 (protocol 113) network format:
         * a run of 16³ sub-chunks from y=0, each carrying block ids + metadata + sky light +
         * block light, then a heightmap, biome map, border and extra-data markers.
         *
         * <p>Layout per the MCPE 1.0 network chunk spec (dktapps):
         * <pre>
         *   byte  subChunkCount
         *   per sub-chunk:
         *     byte      version (0)
         *     byte[4096] block ids   (XZY order)
         *     byte[2048] block meta  (4-bit)
         *     byte[2048] sky light   (4-bit)
         *     byte[2048] block light (4-bit)
         *   byte[512] heightmap (256 shorts)
         *   byte[256] biome ids
         *   byte      border block count
         *   varint    extra-data count
         * </pre>
         *
         * <p>The earlier version omitted the two light arrays and the heightmap, which desynced the
         * client's read pointer after the first sub-chunk and rendered the whole column as garbage.
         */
        private byte[] buildChunkPayload(int chunkX, int chunkZ) {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;

            int topSection = -1;
            for (int sy = 0; sy < 16; sy++) {
                if (sectionHasBlocks(baseX, baseZ, sy)) {
                    topSection = sy;
                }
            }
            int subChunkCount = topSection + 1; // sub-chunks are sent contiguously from y=0

            ByteBuf payload = Unpooled.buffer();
            try {
                payload.writeByte(subChunkCount);
                for (int sy = 0; sy < subChunkCount; sy++) {
                    payload.writeByte(0); // sub-chunk format version (legacy)
                    // Block ids in Bedrock XZY order (x outer, y inner).
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 16; y++) {
                                payload.writeByte(toBedrockId(world.getBlockId(baseX + x, (sy << 4) | y, baseZ + z)));
                            }
                        }
                    }
                    payload.writeZero(2048);        // block metadata nibbles (all 0)
                    payload.writeBytes(FULL_LIGHT); // sky light (full daylight)
                    payload.writeZero(2048);        // block light (none)
                }
                payload.writeZero(512);               // heightmap (256 shorts; client recomputes)
                payload.writeZero(256);               // biome map
                payload.writeByte(0);                 // border block count
                ByteBufUtils.writeVarInt(payload, 0); // extra data count

                byte[] out = new byte[payload.readableBytes()];
                payload.getBytes(payload.readerIndex(), out);
                return out;
            } finally {
                payload.release();
            }
        }

        private boolean sectionHasBlocks(int baseX, int baseZ, int sectionY) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        if (world.getBlockId(baseX + x, (sectionY << 4) | y, baseZ + z) != Blocks.AIR) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Canonical block id → Bedrock 1.1.5 block id. Canonical ids are the classic numeric ids,
         * which legacy Bedrock shares for basic blocks, so this is an identity (clamped to a byte).
         */
        private static int toBedrockId(int canonical) {
            return canonical & 0xFF;
        }

        private static byte[] playStatus() {
            return buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
                ByteBufUtils.writeIntBE(b, PeRakNetServer.PLAY_STATUS_PLAYER_SPAWN);
            });
        }

        /** Serialize one MCPE packet body (id + payload) to a byte array. */
        private static byte[] buildPacket(Consumer<ByteBuf> writer) {
            ByteBuf buf = Unpooled.buffer();
            try {
                writer.accept(buf);
                byte[] out = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), out);
                return out;
            } finally {
                buf.release();
            }
        }

        /** Wrap packets in a zlib batch behind the 0xFE game-packet header and send them reliably. */
        private void sendGameBatch(byte[]... packets) {
            ByteBuf batch = Unpooled.buffer();
            try {
                for (byte[] pk : packets) {
                    ByteBufUtils.writeVarInt(batch, pk.length);
                    batch.writeBytes(pk);
                }
                byte[] uncompressed = new byte[batch.readableBytes()];
                batch.getBytes(batch.readerIndex(), uncompressed);
                byte[] compressed = McpeCompression.deflate(uncompressed, rawDeflate);

                ByteBuf out = Unpooled.buffer(1 + compressed.length);
                out.writeByte(GAME_PACKET_WRAPPER);
                out.writeBytes(compressed);
                session.send(out, RakNetReliability.RELIABLE_ORDERED);
                LOGGER.debug(() -> "[PE] sent game batch (" + packets.length + " packets)");
            } finally {
                batch.release();
            }
        }

        // ===== MCPE Login identity extraction =====

        private static final Pattern JWT_TOKEN =
                Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
        private static final Pattern DISPLAY_NAME =
                Pattern.compile("\"displayName\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern IDENTITY =
                Pattern.compile("\"identity\"\\s*:\\s*\"([0-9a-fA-F-]{32,36})\"");

        private record Identity(UUID uuid, String name) {}

        /**
         * Best-effort extraction of the player's gamertag + UUID from the MCPE Login body
         * (protocol int, then a connection request holding a chain of JWTs). Falls back to a
         * generated identity so a parse failure never blocks the join.
         */
        private static Identity extractIdentity(ByteBuf loginBody) {
            String name = null;
            String identity = null;
            try {
                // Scan the whole Login body for JWT tokens (ASCII "eyJ..." inside the binary
                // framing) — robust to the exact packet layout. Decode each payload and look
                // for the authenticated extraData.
                byte[] all = new byte[loginBody.readableBytes()];
                loginBody.getBytes(loginBody.readerIndex(), all);
                String text = new String(all, StandardCharsets.ISO_8859_1);

                Matcher tokens = JWT_TOKEN.matcher(text);
                while (tokens.find() && (name == null || identity == null)) {
                    String[] parts = tokens.group().split("\\.");
                    if (parts.length < 2) continue;
                    byte[] decoded;
                    try {
                        decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    String payload = new String(decoded, StandardCharsets.UTF_8);
                    if (name == null) name = firstGroup(DISPLAY_NAME, payload);
                    if (identity == null) identity = firstGroup(IDENTITY, payload);
                }
            } catch (Exception e) {
                LOGGER.warn("[PE] Login identity parse failed: " + e);
            }

            UUID uuid = null;
            if (identity != null) {
                try {
                    uuid = UUID.fromString(identity);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (uuid == null) uuid = UUID.randomUUID();
            if (name == null || name.isBlank()) name = "Bedrock-" + Integer.toHexString(uuid.hashCode());
            return new Identity(uuid, name);
        }

        private static String firstGroup(Pattern pattern, String input) {
            Matcher m = pattern.matcher(input);
            return m.find() ? m.group(1) : null;
        }

        private static String pad(String base64Url) {
            int rem = base64Url.length() % 4;
            return rem == 0 ? base64Url : base64Url + "====".substring(rem);
        }
    }
}
