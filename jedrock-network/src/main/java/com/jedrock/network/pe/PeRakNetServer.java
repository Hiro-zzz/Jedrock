package com.jedrock.network.pe;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.World;
import com.jedrock.network.ConnectionListener;
import com.jedrock.utils.JLogger;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerListener;
import com.nukkitx.network.raknet.RakNetServerSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock (PE 1.1.5) server built on the nukkitx RakNet transport.
 *
 * <p>The library owns the RakNet layer (offline handshake, datagram framing, reliability, ACK/NAK,
 * split reassembly). This class only binds the socket and wires each new RakNet session to a
 * {@link PeSession}, which implements just enough of the MCPE 1.1.5 (protocol 113) game layer to
 * get a client into the shared world:
 *
 * <pre>
 *   Login (0x01)                -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo
 *   ResourcePackResponse (0x08) -> StartGame + PlayStatus(PLAYER_SPAWN)
 *   RequestChunkRadius (0x45)   -> ChunkRadiusUpdated + AdventureSettings + chunks + PlayStatus(spawn)
 * </pre>
 */
public final class PeRakNetServer {

    private static final JLogger LOGGER = JLogger.getLogger(PeRakNetServer.class);

    /**
     * RakNet protocol version MCPE 1.1.5 sends in Open Connection Request 1 — confirmed to be 8.
     * Overridable via {@code -Djedrock.pe.raknetProtocolVersion=N} for other client builds.
     */
    public static final int RAKNET_PROTOCOL_VERSION =
            Integer.getInteger("jedrock.pe.raknetProtocolVersion", 8);

    private final InetSocketAddress address;
    private final ProtocolVersion protocol;
    private final ConnectionListener listener;
    private final World world;
    private final ServerProperties properties;

    /** Shared skin registry (uuid → real Bedrock skin) so one PE player's avatar shows on another. */
    private final Map<UUID, McpeSkin.Skin> skins = new ConcurrentHashMap<>();

    private RakNetServer server;

    public PeRakNetServer(InetSocketAddress address, ProtocolVersion protocol, ConnectionListener listener,
                          World world, ServerProperties properties) {
        this.address = address;
        this.protocol = protocol;
        this.listener = listener;
        this.world = world;
        this.properties = properties;
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
            // MCPE server-list advertisement (semicolon-separated). The port fields advertise the
            // server's own bind port — `address` here is the pinging client, not us.
            int port = PeRakNetServer.this.address.getPort();
            int online = listener != null ? listener.getOnlinePlayerCount() : 0;
            String motd = String.join(";",
                    "MCPE",
                    properties.motd(),                              // MOTD line 1
                    Integer.toString(protocol.getProtocolNumber()), // MCPE protocol (113 for 1.1.5)
                    protocol.getVersionName(),                      // "1.1.5"
                    Integer.toString(online),                       // online players
                    Integer.toString(properties.maxPlayers()),      // max players
                    Long.toString(raknet.getGuid()),                // server GUID
                    properties.name(),                              // MOTD line 2 (world/sub-title)
                    "Survival",                                     // game mode
                    "1",                                            // game mode (numeric)
                    Integer.toString(port),                         // IPv4 port
                    Integer.toString(port)                          // IPv6 port
            ) + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            LOGGER.info("[PE] session from " + session.getAddress()
                    + " (mtu=" + session.getMtu() + ", raknet v" + session.getProtocolVersion() + ")");
            session.setListener(new PeSession(session, listener, protocol, world, properties, skins));
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
}
