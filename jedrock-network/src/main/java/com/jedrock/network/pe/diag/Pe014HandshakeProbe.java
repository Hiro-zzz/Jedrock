package com.jedrock.network.pe.diag;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase-0 diagnostic for Bedrock/MCPE 0.14 (protocol 45) support: a raw RakNet <b>offline</b> probe.
 *
 * <p>The nukkitx RakNet server accepts exactly one RakNet protocol version and, on a mismatch, replies
 * IncompatibleProtocolVersion and drops the datagram <em>before</em> any listener sees it — so it can't
 * tell us what version a 0.14 client actually speaks. This standalone probe binds a plain UDP socket
 * and does just enough of the RakNet offline layer to answer that one question without guessing:
 *
 * <ul>
 *   <li>replies to <b>UNCONNECTED_PING</b> (0x01) with a pong, so the server shows up in the 0.14
 *       client's friends/servers list — confirming the client can reach us (loopback / firewall);</li>
 *   <li>reads <b>OPEN_CONNECTION_REQUEST_1</b> (0x05) and logs the RakNet protocol version byte the
 *       client sent (the number we must feed {@code RakNetServer.setProtocolVersion} for 0.14).</li>
 * </ul>
 *
 * <p>It deliberately does <em>not</em> complete the handshake — once we know the version number, the
 * proven nukkitx transport takes over (Phase 1). Run this class's {@code main}, then on the 0.14
 * client add the server on this host at the probe port (default 19133) and try to join; read the
 * logged version off stdout.
 *
 * <p>Throwaway spike; not wired into the server. Delete once 0.14 RakNet is settled.
 */
public final class Pe014HandshakeProbe {

    private Pe014HandshakeProbe() {}

    /** RakNet offline-message magic — stable across every MCPE version. */
    private static final byte[] MAGIC = {
            (byte) 0x00, (byte) 0xff, (byte) 0xff, (byte) 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
    };

    private static final int ID_UNCONNECTED_PING = 0x01;
    private static final int ID_UNCONNECTED_PONG = 0x1C;
    private static final int ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    private static final int ID_OPEN_CONNECTION_REQUEST_2 = 0x07;

    private static final long SERVER_GUID = ThreadLocalRandom.current().nextLong();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.getInteger("jedrock.pe014.probePort", 19133);

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new Handler())
                    .bind(new InetSocketAddress("0.0.0.0", port))
                    .sync();

            System.out.println("[probe] MCPE 0.14 RakNet probe listening on UDP 0.0.0.0:" + port);
            System.out.println("[probe] On the 0.14 client, add this host on port " + port
                    + " and try to join. Watch for the 'RakNet protocol version' line below.");
            System.out.println("[probe] Ctrl+C to stop.");
            Thread.currentThread().join();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static final class Handler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket pkt) {
            ByteBuf in = pkt.content();
            InetSocketAddress sender = pkt.sender();
            if (!in.isReadable()) return;

            int id = in.readUnsignedByte();
            switch (id) {
                case ID_UNCONNECTED_PING -> {
                    long time = in.readableBytes() >= 8 ? in.readLong() : 0L;
                    System.out.println("[probe] UNCONNECTED_PING from " + sender + " → replying pong");
                    ctx.writeAndFlush(pong(ctx, sender, time));
                }
                case ID_OPEN_CONNECTION_REQUEST_1 -> {
                    // [id][magic:16][raknet protocol version:1][zero MTU padding...]
                    int datagramLen = pkt.content().readableBytes() + 1; // + the id byte already read
                    if (in.readableBytes() >= 17) {
                        in.skipBytes(16); // magic
                        int version = in.readUnsignedByte();
                        System.out.println("[probe] *** OPEN_CONNECTION_REQUEST_1 from " + sender
                                + ": RakNet protocol version = " + version
                                + " (datagram ~" + datagramLen + " bytes → padded MTU) ***");
                        System.out.println("[probe] → set RakNetServer.setProtocolVersion(" + version
                                + ") for the 0.14 listener in Phase 1.");
                    } else {
                        System.out.println("[probe] OPEN_CONNECTION_REQUEST_1 from " + sender
                                + " but too short to read version (" + in.readableBytes() + " bytes left)");
                    }
                }
                case ID_OPEN_CONNECTION_REQUEST_2 -> System.out.println(
                        "[probe] OPEN_CONNECTION_REQUEST_2 from " + sender
                                + " (client retried; probe intentionally doesn't complete the handshake)");
                default -> System.out.println("[probe] datagram id=0x" + Integer.toHexString(id)
                        + " from " + sender + " len=" + (in.readableBytes() + 1));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.out.println("[probe] error: " + cause);
        }
    }

    /** UNCONNECTED_PONG: [id][time echo:8][server guid:8][magic:16][motd length:2][motd]. */
    private static DatagramPacket pong(ChannelHandlerContext ctx, InetSocketAddress to, long time) {
        String motd = String.join(";",
                "MCPE", "Jedrock 0.14 probe", "45", "0.14.0", "0", "10",
                Long.toString(SERVER_GUID), "Jedrock", "Survival") + ";";
        byte[] m = motd.getBytes(StandardCharsets.UTF_8);

        ByteBuf out = ctx.alloc().buffer(1 + 8 + 8 + MAGIC.length + 2 + m.length);
        out.writeByte(ID_UNCONNECTED_PONG);
        out.writeLong(time);
        out.writeLong(SERVER_GUID);
        out.writeBytes(MAGIC);
        out.writeShort(m.length);
        out.writeBytes(m);
        return new DatagramPacket(out, to);
    }
}
