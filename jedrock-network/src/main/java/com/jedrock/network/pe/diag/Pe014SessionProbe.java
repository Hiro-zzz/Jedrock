package com.jedrock.network.pe.diag;

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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * Phase-1 diagnostic for MCPE 0.14 (protocol 45): a real nukkitx RakNet server pinned to the RakNet
 * protocol version the 0.14 client speaks (7, learned from {@link Pe014HandshakeProbe}), with a
 * session listener that only <b>logs</b> the first inbound game bytes.
 *
 * <p>Its job is to answer two questions before we write any 0.14 game layer:
 * <ol>
 *   <li>Does nukkitx complete the full RakNet handshake for a v7 client and create a session? (If
 *       "session created / CONNECTED" prints, the proven transport works for 0.14.)</li>
 *   <li>What does the first MCPE packet look like on the wire — is it a {@code 0xFE} zlib batch like
 *       1.1.5, or a raw packet id? The hex dump (and a best-effort inflate) reveals the batch wrapper
 *       and the Login framing, which decides how the 0.14 codec is written.</li>
 * </ol>
 *
 * <p>Run this class's {@code main} (binds UDP 19133 by default), join from the 0.14 client, and read
 * the dumps off stdout. Throwaway spike; not wired into the server.
 */
public final class Pe014SessionProbe {

    private Pe014SessionProbe() {}

    private static final int RAKNET_VERSION_014 = 7;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.getInteger("jedrock.pe014.probePort", 19133);

        RakNetServer server = new RakNetServer(new InetSocketAddress("0.0.0.0", port));
        server.setProtocolVersion(RAKNET_VERSION_014);
        server.setListener(new ServerListener(server));
        server.bind().join();

        System.out.println("[probe1] MCPE 0.14 RakNet session probe on UDP 0.0.0.0:" + port
                + " (RakNet protocol v" + RAKNET_VERSION_014 + ", guid=" + server.getGuid() + ")");
        System.out.println("[probe1] Join from the 0.14 client. Expecting: session CONNECTED, then the"
                + " first MCPE packet dumped below.");
        System.out.println("[probe1] Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    private record ServerListener(RakNetServer server) implements RakNetServerListener {
        @Override
        public boolean onConnectionRequest(InetSocketAddress address) {
            return true;
        }

        @Override
        public byte[] onQuery(InetSocketAddress address) {
            String motd = String.join(";",
                    "MCPE", "Jedrock 0.14 probe", "45", "0.14.0", "0", "10",
                    Long.toString(server.getGuid()), "Jedrock", "Survival") + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            System.out.println("[probe1] *** RakNet session created for " + session.getAddress()
                    + " (mtu=" + session.getMtu() + ", raknet v" + session.getProtocolVersion()
                    + ") — nukkitx accepted the 0.14 handshake ***");
            session.setListener(new SessionListener(session));
        }

        @Override
        public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf b = packet.content();
            if (b.isReadable()) {
                System.out.println("[probe1] unhandled datagram from " + packet.sender()
                        + " id=0x" + Integer.toHexString(b.getUnsignedByte(b.readerIndex())));
            }
        }
    }

    private record SessionListener(RakNetServerSession session) implements RakNetSessionListener {
        @Override
        public void onSessionChangeState(RakNetState state) {
            System.out.println("[probe1] " + session.getAddress() + " -> " + state);
        }

        @Override
        public void onDisconnect(DisconnectReason reason) {
            System.out.println("[probe1] disconnect " + session.getAddress() + " (" + reason + ")");
        }

        @Override
        public void onDirect(ByteBuf buf) {
            System.out.println("[probe1] onDirect " + buf.readableBytes() + " bytes (unexpected)");
        }

        @Override
        public void onEncapsulated(EncapsulatedPacket packet) {
            ByteBuf buf = packet.getBuffer();
            int n = buf.readableBytes();
            byte[] data = new byte[n];
            buf.getBytes(buf.readerIndex(), data);
            int id = n > 0 ? (data[0] & 0xFF) : -1;

            System.out.println("[probe1] --- encapsulated game packet: " + n
                    + " bytes, first id=0x" + Integer.toHexString(id) + " ---");
            System.out.println("[probe1] raw : " + hex(data, 0, Math.min(n, 96)));

            if (id == 0xFE && n > 1) {
                // Possibly a zlib batch like 1.1.5 — try both zlib and raw-deflate.
                byte[] inflated = inflate(data, 1, n - 1, false);
                boolean raw = false;
                if (inflated == null) {
                    inflated = inflate(data, 1, n - 1, true);
                    raw = true;
                }
                if (inflated != null) {
                    System.out.println("[probe1] 0xFE batch inflated (" + (raw ? "raw-deflate" : "zlib")
                            + "): " + inflated.length + " bytes");
                    System.out.println("[probe1] inflated: " + hex(inflated, 0, Math.min(inflated.length, 96)));
                } else {
                    System.out.println("[probe1] 0xFE payload did not zlib/raw-inflate — likely not a"
                            + " compressed batch in 0.14 (maybe a raw packet or a different wrapper).");
                }
            }
        }
    }

    private static byte[] inflate(byte[] data, int off, int len, boolean raw) {
        Inflater inf = new Inflater(raw);
        inf.setInput(data, off, len);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            while (!inf.finished()) {
                int produced = inf.inflate(chunk);
                if (produced == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(chunk, 0, produced);
                if (out.size() > (1 << 20)) break; // 1 MB safety cap
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
    }

    private static String hex(byte[] data, int off, int end) {
        StringBuilder sb = new StringBuilder((end - off) * 3);
        for (int i = off; i < end; i++) {
            sb.append(Character.forDigit((data[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(data[i] & 0xF, 16));
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
