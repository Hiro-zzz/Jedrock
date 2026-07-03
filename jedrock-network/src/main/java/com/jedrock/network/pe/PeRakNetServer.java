package com.jedrock.network.pe;

import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerListener;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetSessionListener;
import com.nukkitx.network.raknet.RakNetState;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Bedrock (PE 1.1.5) listener built on the nukkitx RakNet transport.
 *
 * <p>The library owns the whole RakNet layer — offline handshake, datagram framing,
 * reliability, ACK/NAK and split reassembly. We only:
 * <ul>
 *   <li>answer the unconnected ping ({@link Listener#onQuery}) so the server shows up,</li>
 *   <li>accept connections and observe the session reaching {@link RakNetState#CONNECTED},</li>
 *   <li>log the first MCPE game batch ({@code 0xFE}) that arrives once connected.</li>
 * </ul>
 *
 * <p>Milestone scope: get the client to RakNet-connected and prove the MCPE Login batch
 * arrives. Actually decoding that batch (0xFE + zlib + login) is the next step.
 */
public final class PeRakNetServer {

    private static final JLogger LOGGER = JLogger.getLogger(PeRakNetServer.class);

    /**
     * RakNet protocol version byte MCPE 1.1.5 sends in Open Connection Request 1.
     *
     * <p>The library default is 10 (modern Bedrock uses 11); 1.1.5 is older. If the client
     * reports "incompatible protocol" and never reaches a session, try the other candidates
     * (7, 9, 10) — no rebuild needed, override via the system property
     * {@code -Djedrock.pe.raknetProtocolVersion=N}. Once a session is created,
     * {@link RakNetServerSession#getProtocolVersion()} confirms the negotiated value
     * (logged in {@link Listener#onSessionCreation}).
     */
    public static final int RAKNET_PROTOCOL_VERSION =
            Integer.getInteger("jedrock.pe.raknetProtocolVersion", 8);

    private final InetSocketAddress address;
    private final ProtocolVersion protocol;

    private RakNetServer server;

    public PeRakNetServer(InetSocketAddress address, ProtocolVersion protocol) {
        this.address = address;
        this.protocol = protocol;
    }

    public void bind() throws Exception {
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
            // MCPE server-list advertisement (semicolon-separated).
            String motd = String.join(";",
                    "MCPE",
                    "Jedrock",                                   // MOTD line 1
                    Integer.toString(protocol.getProtocolNumber()), // MCPE protocol (113 for 1.1.5)
                    protocol.getVersionName(),                   // "1.1.5"
                    "0",                                         // online players
                    "10",                                        // max players
                    Long.toString(raknet.getGuid()),             // server GUID
                    "Jedrock PE",                                // MOTD line 2
                    "Survival",                                  // game mode
                    "1",                                         // game mode (numeric)
                    Integer.toString(address.getPort()),         // IPv4 port
                    Integer.toString(address.getPort())          // IPv6 port
            ) + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            LOGGER.info("[PE] session from " + session.getAddress()
                    + " (mtu=" + session.getMtu() + ", raknet v" + session.getProtocolVersion() + ")");
            session.setListener(new SessionHandler(session));
        }

        @Override
        public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket packet) {
            // Best-effort visibility for anything the library didn't recognise.
            ByteBuf buf = packet.content();
            if (buf.isReadable()) {
                LOGGER.debug(() -> "[PE] unhandled datagram from " + packet.sender()
                        + " id=0x" + Integer.toHexString(buf.getUnsignedByte(buf.readerIndex())));
            }
        }
    }

    /** Per-session callbacks (state, disconnect, and inbound game data). */
    private static final class SessionHandler implements RakNetSessionListener {

        private final RakNetServerSession session;

        SessionHandler(RakNetServerSession session) {
            this.session = session;
        }

        @Override
        public void onSessionChangeState(RakNetState state) {
            LOGGER.info("[PE] " + session.getAddress() + " -> " + state);
            if (state == RakNetState.CONNECTED) {
                LOGGER.info("[PE] RakNet CONNECTED: " + session.getAddress()
                        + " — awaiting MCPE Login batch");
            }
        }

        @Override
        public void onDisconnect(DisconnectReason reason) {
            LOGGER.info("[PE] disconnect " + session.getAddress() + " (" + reason + ")");
        }

        @Override
        public void onEncapsulated(EncapsulatedPacket packet) {
            // Copy the payload out without consuming the library's buffer.
            ByteBuf buf = packet.getBuffer();
            int size = buf.readableBytes();
            if (size < 1) return;
            byte[] payload = new byte[size];
            buf.getBytes(buf.readerIndex(), payload);

            int wrapperId = payload[0] & 0xFF;
            if (wrapperId != 0xFE) {
                LOGGER.info("[PE] encapsulated payload id=0x" + Integer.toHexString(wrapperId) + " (" + size + " bytes)");
                return;
            }
            decodeBatch(java.util.Arrays.copyOfRange(payload, 1, payload.length));
        }

        /** Inflate a 0xFE batch and log each inner MCPE packet id/size (foundation for the join flow). */
        private void decodeBatch(byte[] compressed) {
            McpeCompression.Inflated inflated = McpeCompression.inflate(compressed);
            if (inflated == null) {
                LOGGER.warn("[PE] failed to inflate 0xFE batch (" + compressed.length + " bytes): "
                        + hexPreview(compressed, 16));
                return;
            }
            LOGGER.info("[PE] batch inflated: " + inflated.data().length + " bytes ("
                    + (inflated.raw() ? "raw deflate" : "zlib") + ")");

            ByteBuf batch = io.netty.buffer.Unpooled.wrappedBuffer(inflated.data());
            try {
                int index = 0;
                while (batch.isReadable()) {
                    int len = ByteBufUtils.readVarInt(batch);
                    if (len <= 0 || len > batch.readableBytes()) {
                        LOGGER.warn("[PE] malformed batch entry: len=" + len + ", remaining=" + batch.readableBytes());
                        break;
                    }
                    ByteBuf pk = batch.readSlice(len);
                    int mcpeId = ByteBufUtils.readVarInt(pk);
                    LOGGER.info("[PE]   packet #" + (index++) + " id=0x" + Integer.toHexString(mcpeId)
                            + " (len " + len + ")");
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[PE] error parsing batch: " + e.getMessage());
            } finally {
                batch.release();
            }
        }

        private static String hexPreview(byte[] data, int max) {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(max, data.length);
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02x ", data[i]));
            }
            if (data.length > n) sb.append("...");
            return sb.toString().trim();
        }

        @Override
        public void onDirect(ByteBuf buf) {
            // Raw, non-encapsulated data — not expected in normal flow.
        }
    }
}
