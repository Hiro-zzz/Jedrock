package com.jedrock.network.pe;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.network.ConnectionListener;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
    private static final int ID_ADVENTURE_SETTINGS = 0x37;
    private static final int ID_FULL_CHUNK_DATA = 0x3A;
    private static final int ID_REQUEST_CHUNK_RADIUS = 0x45;
    private static final int ID_CHUNK_RADIUS_UPDATED = 0x46;
    private static final int ID_NETWORK_CHUNK_PUBLISHER_UPDATE = 0x79;

    private static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    private static final int PLAY_STATUS_PLAYER_SPAWN = 3;

    // MCPE TextPacket types
    private static final int TEXT_TYPE_RAW = 0;
    private static final int TEXT_TYPE_CHAT = 1;

    private final InetSocketAddress address;
    private final ProtocolVersion protocol;
    private final ConnectionListener listener;

    private RakNetServer server;

    public PeRakNetServer(InetSocketAddress address, ProtocolVersion protocol, ConnectionListener listener) {
        this.address = address;
        this.protocol = protocol;
        this.listener = listener;
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
            session.setListener(new SessionHandler(session, listener, protocol));
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

        private volatile boolean loggedIn = false;
        private volatile UUID uuid;
        private volatile String username;
        /** Compression mode observed on inbound batches; reused for outbound (chat etc.). */
        private volatile boolean rawDeflate = false;

        SessionHandler(RakNetServerSession session, ConnectionListener listener, ProtocolVersion protocol) {
            this.session = session;
            this.listener = listener;
            this.protocol = protocol;
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
            // Bedrock PlayerList needs skin data — implemented together with the PE world step.
        }

        @Override
        public void removeFromTab(UUID uuid) {
            // See addToTab.
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
                    LOGGER.info("[PE] chunk radius requested → deploying world (3x3 chunks + spawn)");
                    sendWorld();
                    registerPlayer(); // fully in-game now — hand it to the core like a JE player
                }
                case ID_TEXT -> handleInboundText(pk);
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

        /** Reply to the resource-pack response with the world's StartGame + a spawn nudge. */
        private void sendStartGame() {
            byte[] startGame = buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_START_GAME);

                // Player entity ids + gamemode
                ByteBufUtils.writeSignedVarLong(b, 1L); // entity unique id
                ByteBufUtils.writeVarLong(b, 1L);       // entity runtime id
                ByteBufUtils.writeSignedVarInt(b, 1);   // gamemode (creative)

                // Position + rotation (little-endian floats)
                b.writeFloatLE(0.0f);   // x
                b.writeFloatLE(70.0f);  // y
                b.writeFloatLE(0.0f);   // z
                b.writeFloatLE(0.0f);   // pitch
                b.writeFloatLE(0.0f);   // yaw

                // World generation basics
                ByteBufUtils.writeSignedVarInt(b, 12345); // seed
                ByteBufUtils.writeSignedVarInt(b, 0);     // dimension (overworld)
                ByteBufUtils.writeSignedVarInt(b, 1);     // generator (infinite)
                ByteBufUtils.writeSignedVarInt(b, 1);     // world gamemode (creative)
                ByteBufUtils.writeSignedVarInt(b, 1);     // difficulty (easy)

                // World spawn block coords
                ByteBufUtils.writeSignedVarInt(b, 0);   // spawn x
                ByteBufUtils.writeSignedVarInt(b, 70);  // spawn y
                ByteBufUtils.writeSignedVarInt(b, 0);   // spawn z

                // Flags + environment
                b.writeBoolean(true);                   // achievements disabled
                ByteBufUtils.writeSignedVarInt(b, 0);   // day cycle stop time
                b.writeBoolean(false);                  // edu mode
                b.writeFloatLE(0.0f);                   // rain level
                b.writeFloatLE(0.0f);                   // lightning level

                // Multiplayer settings
                b.writeBoolean(true);                   // is multiplayer
                b.writeBoolean(true);                   // broadcast to LAN
                b.writeBoolean(false);                  // broadcast to Xbox Live

                // Extra flags
                b.writeBoolean(true);                   // commands enabled
                b.writeBoolean(false);                  // texture packs required

                ByteBufUtils.writeVarInt(b, 0);         // game rules count

                ByteBufUtils.writeString(b, "jedrock_level");
                ByteBufUtils.writeString(b, "Jedrock PE World");
                ByteBufUtils.writeString(b, "");        // premium world template id

                b.writeBoolean(false);                  // is trial
                b.writeLongLE(0L);                      // current world tick
            });
            byte[] spawnStatus = playStatus(PLAY_STATUS_PLAYER_SPAWN);
            // NOTE: canonically PLAYER_SPAWN is sent once, after chunks. We also nudge here
            // (and again in sendWorld) — kept because it is what a 1.1.5 client accepts today.
            sendGameBatch(startGame, spawnStatus);
        }

        /** Reply to the chunk-radius request: publish a small flat 3x3 world and spawn the player. */
        private void sendWorld() {
            List<byte[]> packets = new ArrayList<>();

            packets.add(buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_CHUNK_RADIUS_UPDATED);
                ByteBufUtils.writeSignedVarInt(b, 2); // keep the radius small
            }));
            packets.add(buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_NETWORK_CHUNK_PUBLISHER_UPDATE);
                ByteBufUtils.writeSignedVarInt(b, 0);   // center x
                ByteBufUtils.writeSignedVarInt(b, 70);  // center y
                ByteBufUtils.writeSignedVarInt(b, 0);   // center z
                ByteBufUtils.writeVarInt(b, 2 * 16);    // publish radius in blocks
            }));
            packets.add(buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_ADVENTURE_SETTINGS);
                ByteBufUtils.writeVarInt(b, 0);   // flags
                ByteBufUtils.writeVarInt(b, 2);   // command permission (OP)
                ByteBufUtils.writeVarInt(b, 0);   // action permissions
                ByteBufUtils.writeVarInt(b, 2);   // permission level (OP)
                ByteBufUtils.writeVarInt(b, 0);   // custom extension flags
                ByteBufUtils.writeVarLong(b, 1L); // player entity unique id
            }));

            // 3x3 grid of empty (air) chunks — the flat-world illusion.
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    int chunkX = cx;
                    int chunkZ = cz;
                    packets.add(buildPacket(b -> {
                        ByteBufUtils.writeVarInt(b, ID_FULL_CHUNK_DATA);
                        ByteBufUtils.writeSignedVarInt(b, chunkX);
                        ByteBufUtils.writeSignedVarInt(b, chunkZ);
                        ByteBufUtils.writeVarInt(b, 1 + 256 + 1); // chunk payload length
                        b.writeByte(0);                           // 0 sub-chunks (only air)
                        b.writeZero(256);                         // biome map
                        ByteBufUtils.writeVarInt(b, 0);           // extra data
                    }));
                }
            }

            packets.add(playStatus(PLAY_STATUS_PLAYER_SPAWN)); // finally kick the client out of the load screen
            sendGameBatch(packets.toArray(new byte[0][]));
        }

        private static byte[] playStatus(int status) {
            return buildPacket(b -> {
                ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
                ByteBufUtils.writeIntBE(b, status);
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
