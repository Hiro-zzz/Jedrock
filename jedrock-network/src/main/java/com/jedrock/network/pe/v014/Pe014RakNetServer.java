package com.jedrock.network.pe.v014;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.world.World;
import com.jedrock.network.ConnectionListener;
import com.jedrock.utils.JLogger;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerListener;
import com.nukkitx.network.raknet.RakNetServerSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Bedrock/MCPE 0.14 (protocol 45) server on its own UDP port. Structurally the same as the 1.1.5
 * {@code PeRakNetServer} but pinned to the RakNet protocol version a 0.14 client speaks (7, confirmed
 * with a real client) and wiring each session to a {@link PeSession014}. It runs on a separate port
 * because nukkitx binds one RakNet version per socket, and 0.14 (v7) and 1.1.5 (v8) differ.
 */
public final class Pe014RakNetServer {

    private static final JLogger LOGGER = JLogger.getLogger(Pe014RakNetServer.class);

    /** RakNet protocol version MCPE 0.14 sends in Open Connection Request 1 (confirmed 7). */
    public static final int RAKNET_PROTOCOL_VERSION =
            Integer.getInteger("jedrock.pe014.raknetProtocolVersion", 7);

    private final InetSocketAddress address;
    private final ConnectionListener listener;
    private final World world;
    private final ServerProperties properties;

    private RakNetServer server;

    public Pe014RakNetServer(InetSocketAddress address, ConnectionListener listener,
                             World world, ServerProperties properties) {
        this.address = address;
        this.listener = listener;
        this.world = world;
        this.properties = properties;
    }

    public void bind() {
        RakNetServer raknet = new RakNetServer(address);
        raknet.setProtocolVersion(RAKNET_PROTOCOL_VERSION);
        raknet.setListener(new Listener(raknet));
        raknet.bind().join();
        this.server = raknet;
        LOGGER.info("Listening on " + address + " for MCPE 0.14 (Bedrock/RakNet, protocol v"
                + RAKNET_PROTOCOL_VERSION + ")");
    }

    public void close() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private final class Listener implements RakNetServerListener {
        private final RakNetServer raknet;

        Listener(RakNetServer raknet) {
            this.raknet = raknet;
        }

        @Override
        public boolean onConnectionRequest(InetSocketAddress address) {
            return true;
        }

        @Override
        public byte[] onQuery(InetSocketAddress address) {
            int port = Pe014RakNetServer.this.address.getPort();
            int online = listener != null ? listener.getOnlinePlayerCount() : 0;
            String motd = String.join(";",
                    "MCPE", properties.motd(), "45", "0.14.0",
                    Integer.toString(online), Integer.toString(properties.maxPlayers()),
                    Long.toString(raknet.getGuid()), properties.name(), "Survival", "1",
                    Integer.toString(port), Integer.toString(port)) + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            LOGGER.info("[0.14] session from " + session.getAddress() + " (mtu=" + session.getMtu() + ")");
            session.setListener(new PeSession014(session, listener, world));
        }

        @Override
        public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket packet) { }
    }
}
