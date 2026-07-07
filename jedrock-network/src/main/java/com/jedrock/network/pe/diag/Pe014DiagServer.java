package com.jedrock.network.pe.diag;

import com.jedrock.network.pe.v014.Mcpe014Login;
import com.jedrock.network.pe.v014.Mcpe014Packets;
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

/**
 * Phase-2 driver for MCPE 0.14 (protocol 45): a nukkitx RakNet server (v7) that actually decodes the
 * 0.14 game layer far enough to identify the player, then runs a <b>login-accept hypothesis</b> and
 * logs the client's reaction. This is the tight test loop that will grow into the real 0.14 session.
 *
 * <p>What it does per inbound game message:
 * <ul>
 *   <li>strips the observed one-byte wrapper {@code 0x8e} (present on the client's Login) to reach the
 *       inner packet id;</li>
 *   <li>on Login ({@code 0x8f}) it decodes username + protocol via {@link Mcpe014Login} and replies
 *       with a hypothesised PlayStatus(LOGIN_SUCCESS) — {@code [0x8e][0x90][int32 0]} — mirroring the
 *       inbound wrapper and the low-id scheme's Login→PlayStatus adjacency (0x8f→0x90);</li>
 *   <li>logs every other inbound packet's id / length / hex, so the next client test reveals whether
 *       the client advanced past "Logging in" and what packets it sends next (the protocol-45 packet
 *       vocabulary we still need to map).</li>
 * </ul>
 *
 * <p>Throwaway spike; not wired into the server. The {@code 0x90} PlayStatus id and its int32 body are
 * a principled guess to be confirmed/corrected from the client's response.
 */
public final class Pe014DiagServer {

    private Pe014DiagServer() {}

    private static final int RAKNET_VERSION_014 = 7;

    private static final int WRAPPER = Mcpe014Packets.WRAPPER;   // 0x8e
    private static final int ID_LOGIN = Mcpe014Login.PACKET_ID;  // 0x8f

    // Hardcoded spawn for this diagnostic (no world yet): the client should appear standing here.
    private static final int SPAWN_X = 0, SPAWN_Y = 70, SPAWN_Z = 0;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.getInteger("jedrock.pe014.probePort", 19133);

        RakNetServer server = new RakNetServer(new InetSocketAddress("0.0.0.0", port));
        server.setProtocolVersion(RAKNET_VERSION_014);
        server.setListener(new ServerListener(server));
        server.bind().join();

        System.out.println("[0.14] Phase-2 diag server on UDP 0.0.0.0:" + port
                + " (RakNet v" + RAKNET_VERSION_014 + ", guid=" + server.getGuid() + ")");
        System.out.println("[0.14] Join from the 0.14 client; watch for 'Login:' then the client's reaction.");
        System.out.println("[0.14] Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    private record ServerListener(RakNetServer server) implements RakNetServerListener {
        @Override public boolean onConnectionRequest(InetSocketAddress address) { return true; }

        @Override
        public byte[] onQuery(InetSocketAddress address) {
            String motd = String.join(";",
                    "MCPE", "Jedrock 0.14", "45", "0.14.0", "0", "10",
                    Long.toString(server.getGuid()), "Jedrock", "Survival") + ";";
            return motd.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void onSessionCreation(RakNetServerSession session) {
            System.out.println("[0.14] session created for " + session.getAddress()
                    + " (mtu=" + session.getMtu() + ")");
            session.setListener(new SessionListener(session));
        }

        @Override
        public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket packet) { }
    }

    private record SessionListener(RakNetServerSession session) implements RakNetSessionListener {
        @Override
        public void onSessionChangeState(RakNetState state) {
            System.out.println("[0.14] " + session.getAddress() + " -> " + state);
        }

        @Override
        public void onDisconnect(DisconnectReason reason) {
            System.out.println("[0.14] disconnect " + session.getAddress() + " (" + reason + ")");
        }

        @Override
        public void onDirect(ByteBuf buf) { }

        @Override
        public void onEncapsulated(EncapsulatedPacket packet) {
            ByteBuf buf = packet.getBuffer();
            int n = buf.readableBytes();
            if (n < 1) return;
            byte[] data = new byte[n];
            buf.getBytes(buf.readerIndex(), data);

            int first = data[0] & 0xFF;
            boolean wrapped = first == WRAPPER;
            int idOffset = wrapped ? 1 : 0;
            if (wrapped && n < 2) return;
            int id = data[idOffset] & 0xFF;
            int bodyOffset = idOffset + 1;

            System.out.println("[0.14] inbound wrapped=" + wrapped + " id=0x" + Integer.toHexString(id)
                    + " len=" + n);

            if (id == ID_LOGIN) {
                try {
                    ByteBuf body = Unpooled.wrappedBuffer(data, bodyOffset, n - bodyOffset);
                    Mcpe014Login.Identity idn = Mcpe014Login.decode(body);
                    System.out.println("[0.14] *** Login: name='" + idn.name() + "' protocol="
                            + idn.protocol() + " uuid=" + idn.uuid() + " ***");
                    sendLoginSequence();
                    System.out.println("[0.14] → sent full login sequence (PlayStatus + StartGame +"
                            + " SetTime + SetSpawnPosition + SetHealth + SetDifficulty). Watch whether"
                            + " the client reaches terrain-load and requests a chunk radius (0xc8).");
                } catch (RuntimeException e) {
                    System.out.println("[0.14] failed to decode Login: " + e);
                }
            } else if (id == Mcpe014Packets.ID_REQUEST_CHUNK_RADIUS) {
                ByteBuf body = Unpooled.wrappedBuffer(data, bodyOffset, n - bodyOffset);
                int radius = body.readableBytes() >= 4 ? body.readInt() : 8;
                System.out.println("[0.14] *** RequestChunkRadius: " + radius
                        + " → replying ChunkRadiusUpdate (StartGame accepted!). No chunks sent yet. ***");
                sendWrapped(b -> Mcpe014Packets.chunkRadiusUpdate(b, Math.min(radius, 4)));
            } else {
                System.out.println("[0.14] body hex: " + hex(data, bodyOffset, Math.min(n, bodyOffset + 64)));
            }
        }

        /** Send the full MCPE 0.14 login-accept sequence (each packet 0x8e-wrapped, like PocketMine). */
        private void sendLoginSequence() {
            sendWrapped(b -> Mcpe014Packets.playStatus(b, Mcpe014Packets.PLAY_STATUS_LOGIN_SUCCESS));
            sendWrapped(b -> Mcpe014Packets.startGame(b,
                    -1,                 // seed
                    0,                  // dimension: overworld
                    1,                  // generator: infinite
                    1,                  // gamemode: creative
                    0L,                 // eid: self is always 0
                    SPAWN_X, SPAWN_Y, SPAWN_Z,
                    SPAWN_X + 0.5f, SPAWN_Y, SPAWN_Z + 0.5f));
            sendWrapped(b -> Mcpe014Packets.setTime(b, 0, true));
            sendWrapped(b -> Mcpe014Packets.setSpawnPosition(b, SPAWN_X, SPAWN_Y, SPAWN_Z));
            sendWrapped(b -> Mcpe014Packets.setHealth(b, 20));
            sendWrapped(b -> Mcpe014Packets.setDifficulty(b, 2));
        }

        /** Wrap one packet in the 0x8e game header and send it reliably-ordered (channel 0). */
        private void sendWrapped(java.util.function.Consumer<ByteBuf> body) {
            ByteBuf out = Unpooled.buffer();
            out.writeByte(WRAPPER);
            body.accept(out);
            session.send(out, RakNetReliability.RELIABLE_ORDERED);
        }
    }

    private static String hex(byte[] data, int off, int end) {
        StringBuilder sb = new StringBuilder(Math.max(0, (end - off) * 3));
        for (int i = off; i < end; i++) {
            sb.append(Character.forDigit((data[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(data[i] & 0xF, 16));
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
