package com.jedrock.network;

import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.network.pipeline.LazyPacketDecoder;
import com.jedrock.network.pipeline.VarintFrameDecoder;
import com.jedrock.network.pipeline.VarintLengthEncoder;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void bind(SocketAddress address, ProtocolVersion protocol) throws Exception {
        if (protocol.isBedrock()) {
            // RakNet over UDP for Bedrock
            new Bootstrap()
                    .group(workerGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new BedrockRakNetHandler(this, protocol))
                    .bind(address).sync(); // throws if the bind fails
            active = true;
            logger.info("Listening on " + address + " for " + protocol.getVersionName() + " (Bedrock/RakNet)");
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

            // Final handler — owns the Connection and lazy packet dispatch
            p.addLast("connection-handler", new ConnectionHandler(NettyNetworkServer.this, protocol));
        }
    }

    /**
     * Minimal RakNet handler for Bedrock 1.1.5.
     */
    private static class BedrockRakNetHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private static final JLogger logger = JLogger.getLogger(BedrockRakNetHandler.class);

        private final NettyNetworkServer server;
        private final ProtocolVersion protocol;

        private final Map<InetSocketAddress, RakNetSession> sessions = new ConcurrentHashMap<>();

        BedrockRakNetHandler(NettyNetworkServer server, ProtocolVersion protocol) {
            this.server = server;
            this.protocol = protocol;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf buf = packet.content();
            if (buf.readableBytes() < 1) return;

            int id = buf.readUnsignedByte();
            InetSocketAddress sender = packet.sender();

            if (id == 0x01) {
                handleUnconnectedPing(ctx, sender, buf);
            } else if (id == 0x05) {
                handleOpenConnectionRequest1(ctx, sender, buf);
            } else if (id == 0x07) {
                handleOpenConnectionRequest2(ctx, sender, buf);
            } else if (id == 0x09) {
                handleConnectionRequest(ctx, sender, buf);
            } else if (id == 0x13) {
                handleNewIncomingConnection(sender);
            } else if ((id & 0x80) != 0) {
                handleEncapsulated(ctx, sender, buf);
            }
        }

        private void handleUnconnectedPing(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf in) {
            in.skipBytes(24);

            String motd = "MCPE;Jedrock PE Server;113;1.1.5;0;10;0;World;Survival;1;19132;19133;";

            ByteBuf out = ctx.alloc().buffer();
            out.writeByte(0x1C);
            out.writeLong(System.currentTimeMillis());
            out.writeLong(0);
            writeMagic(out);
            out.writeShort(motd.length());
            out.writeCharSequence(motd, StandardCharsets.UTF_8);

            ctx.writeAndFlush(new DatagramPacket(out, sender));
        }

        private void handleOpenConnectionRequest1(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
            buf.skipBytes(16); // magic
            buf.readByte(); // protocol
            int mtu = Math.min(1400, buf.readableBytes() + 28);

            ByteBuf reply = ctx.alloc().buffer();
            reply.writeByte(0x06); // Open Connection Reply 1
            writeMagic(reply);
            reply.writeLong(0);
            reply.writeByte(0);
            reply.writeShort(mtu);
            ctx.writeAndFlush(new DatagramPacket(reply, sender));
        }

        private void handleOpenConnectionRequest2(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
            buf.skipBytes(16); // magic
            buf.skipBytes(7); // client address
            int mtu = buf.readUnsignedShort();
            long clientGuid = buf.readLong();

            sessions.put(sender, new RakNetSession(sender, clientGuid, mtu));

            ByteBuf reply = ctx.alloc().buffer();
            reply.writeByte(0x08); // Open Connection Reply 2
            writeMagic(reply);
            reply.writeLong(0);
            reply.writeByte(4);
            reply.writeInt(0);
            reply.writeShort(sender.getPort());
            reply.writeShort(mtu);
            reply.writeByte(0);
            ctx.writeAndFlush(new DatagramPacket(reply, sender));
        }

        private void handleConnectionRequest(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
            buf.skipBytes(16); // client guid + time

            ByteBuf reply = ctx.alloc().buffer();
            reply.writeByte(0x10); // Connection Request Accepted
            reply.writeByte(4);
            reply.writeInt(0);
            reply.writeShort(sender.getPort());
            reply.writeShort(0);
            for (int i = 0; i < 10; i++) reply.writeByte(0);
            reply.writeLong(System.currentTimeMillis());
            reply.writeLong(0);
            ctx.writeAndFlush(new DatagramPacket(reply, sender));
        }

        private void handleNewIncomingConnection(InetSocketAddress sender) {
            RakNetSession s = sessions.get(sender);
            if (s != null) {
                s.fullyConnected = true;
                logger.info("PE 1.1.5 client fully connected via RakNet: " + sender);
            }
        }

        private void handleEncapsulated(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf buf) {
            RakNetSession s = sessions.get(sender);
            if (s == null || !s.fullyConnected) return;

            // Crude skip for demo
            if (buf.readableBytes() < 4) return;
            buf.skipBytes(1);
            buf.skipBytes(2);

            if (buf.readableBytes() > 0) {
                byte mcpeId = buf.readByte();
                logger.info("[PE 1.1.5] MCPE packet 0x" + Integer.toHexString(mcpeId & 0xFF));

                // For demo, when we see Login (typically 0x01 in old Bedrock), send PlayStatus
                if (mcpeId == 0x01) {
                    sendPlayStatus(ctx, sender);
                }
            }
        }

        private void sendPlayStatus(ChannelHandlerContext ctx, InetSocketAddress target) {
            ByteBuf payload = ctx.alloc().buffer();
            payload.writeInt(0); // LOGIN_SUCCESS

            ByteBuf rak = ctx.alloc().buffer();
            rak.writeByte(0x84); // unreliable
            rak.writeByte(0x02); // PlayStatus
            rak.writeBytes(payload);

            ctx.writeAndFlush(new DatagramPacket(rak, target));
            logger.info("Sent PlayStatus to PE client - should allow basic join");
        }

        private void writeMagic(ByteBuf buf) {
            buf.writeBytes(new byte[]{
                0x00, (byte)0xff, (byte)0xff, 0x00,
                (byte)0xfe, (byte)0xfe, (byte)0xfe, (byte)0xfe,
                (byte)0xfd, (byte)0xfd, (byte)0xfd, (byte)0xfd,
                0x12, 0x34, 0x56, 0x78
            });
        }

        private static class RakNetSession {
            InetSocketAddress address;
            long guid;
            int mtu;
            boolean fullyConnected = false;

            RakNetSession(InetSocketAddress address, long guid, int mtu) {
                this.address = address;
                this.guid = guid;
                this.mtu = mtu;
            }
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
            connection = new JedrockConnection(ctx.channel(), protocol, server.listener);
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

