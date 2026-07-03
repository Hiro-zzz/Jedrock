package com.jedrock.network;

import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.network.pe.PeRakNetServer;
import com.jedrock.network.pipeline.LazyPacketDecoder;
import com.jedrock.network.pipeline.VarintFrameDecoder;
import com.jedrock.network.pipeline.VarintLengthEncoder;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production-grade Netty NetworkServer for Jedrock.
 *
 * Core philosophy:
 *   "Only what must be visible is materialized. The rest stays pure bytes."
 *
 * - All inbound traffic becomes LazyPacket (id + raw payload)
 * - Framed correctly for the protocol
 * - JE uses full TCP + VarInt pipeline (implemented)
 * - PE will be RakNet over UDP (stub prepared)
 */

public class NettyNetworkServer implements NetworkServer {

    private final JLogger logger = JLogger.getLogger(NettyNetworkServer.class);

    // Shared groups — efficient for multiple binds
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private final ConcurrentHashMap<Channel, Connection> connections = new ConcurrentHashMap<>();

    private volatile boolean active = false;
    private volatile ConnectionListener listener;
    private volatile com.jedrock.api.world.World world;
    private volatile PeRakNetServer peServer;

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void setWorld(com.jedrock.api.world.World world) {
        this.world = world;
    }

    @Override
    public void bind(SocketAddress address, ProtocolVersion protocol) throws Exception {
        if (protocol.isBedrock()) {
            // Bedrock: hand the whole RakNet layer to the dedicated PE server.
            PeRakNetServer pe = new PeRakNetServer((InetSocketAddress) address, protocol, listener, world);
            pe.bind();
            this.peServer = pe;
            active = true;
        } else {
            // Java Edition - TCP
            new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new JavaEditionChannelInitializer(protocol))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .bind(address).sync(); // throws if the bind fails
            active = true;
            logger.info("Listening on " + address + " for " + protocol.getVersionName() + " (Java Edition)");
        }
    }

    @Override
    public void shutdown() {
        active = false;
        logger.info("Shutting down network...");

        // Close all active connections first
        for (Connection conn : connections.values()) {
            try {
                conn.close();
            } catch (Exception ignored) {}
        }
        connections.clear();

        if (peServer != null) {
            peServer.close();
            peServer = null;
        }

        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
        logger.info("Network shutdown complete.");
    }

    @Override
    public Collection<Connection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Should be called every tick from the game loop.
     */
    public void tick(long currentTick) {
        for (Connection conn : connections.values()) {
            if (conn instanceof JedrockConnection jc) {
                jc.tick(currentTick);
            }
        }
    }

    // === Connection registry ===

    void registerConnection(Channel ch, Connection conn) {
        connections.put(ch, conn);
    }

    void unregisterConnection(Channel ch) {
        connections.remove(ch);
    }

    // ==================== PIPELINE INITIALIZERS ====================

    /**
     * Java Edition 1.12.2 pipeline.
     *
     * Inbound:
     *   raw TCP bytes
     *     → VarintFrameDecoder     (extracts length-prefixed frames)
     *     → LazyPacketDecoder      (extracts ID + keeps payload as raw ByteBuf)
     *     → ConnectionHandler      (creates JedrockConnection + forwards LazyPacket)
     *
     * Outbound:
     *   ByteBuf (id+payload)   // JedrockConnection.send(ClientboundPacket) writes [VarInt id][payload]
     *     → VarintLengthEncoder (prepends length)
     *     → wire
     */
    private class JavaEditionChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final ProtocolVersion protocol;

        JavaEditionChannelInitializer(ProtocolVersion protocol) {
            this.protocol = protocol;
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            ChannelPipeline p = ch.pipeline();

            // Inbound framing + lazy packet creation
            p.addLast("frame-decoder", new VarintFrameDecoder());
            p.addLast("packet-decoder", new LazyPacketDecoder());

            // Outbound:
            // We send already prepared [VarInt packetId + payload]
            // LengthEncoder prepends the total length.
            p.addLast("length-encoder", new VarintLengthEncoder());

            // Final handler — owns the Connection. Packet dispatch is delegated to ProtocolHandler inside JedrockConnection.
            p.addLast("connection-handler", new ConnectionHandler(NettyNetworkServer.this, protocol));
        }
    }


    /**
     * Final inbound handler. Creates the JedrockConnection and forwards LazyPackets to it.
     */
    private static class ConnectionHandler extends ChannelInboundHandlerAdapter {

        private static final JLogger handlerLogger = JLogger.getLogger(ConnectionHandler.class);

        private final NettyNetworkServer server;
        private final ProtocolVersion protocol;
        private JedrockConnection connection;

        ConnectionHandler(NettyNetworkServer server, ProtocolVersion protocol) {
            this.server = server;
            this.protocol = protocol;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            connection = new JedrockConnection(ctx.channel(), protocol, server.listener, server.world);
            server.registerConnection(ctx.channel(), connection);

            // New connection starts in HANDSHAKE state (for JE)
            // The first packet (usually 0x00 Handshake) will be received as LazyPacket
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (connection != null) {
                connection.notifyDisconnected();
                server.unregisterConnection(ctx.channel());
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof LazyPacket lazy && connection != null) {
                connection.handleInboundPacket(lazy);
            } else {
                // Unknown message — release if it's a buffer
                if (msg instanceof ByteBuf buf) {
                    buf.release();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            handlerLogger.warn("Connection error from " + ctx.channel().remoteAddress() + ": " + cause.getMessage());
            ctx.close();
        }
    }
}

