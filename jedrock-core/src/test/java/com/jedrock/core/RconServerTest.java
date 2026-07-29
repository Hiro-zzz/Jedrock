package com.jedrock.core;

import com.jedrock.core.rcon.RconPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RCON listener against a real socket on a real port: authenticate, run a command, read the reply.
 *
 * <p>Worth doing over a socket rather than against a mocked stream, because everything that actually goes
 * wrong here is at that boundary — a reply that never gets flushed, a refused password that leaves the
 * connection open, a command answered before the client was allowed to ask.
 */
class RconServerTest {

    private static final String PASSWORD = "hunter2";

    private RconServer server;
    private final List<String> ran = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** A listener on an ephemeral port, whose console records what it was asked to run. */
    private int start(Runnable deferred) {
        server = new RconServer((line, sender) -> {
            ran.add(line);
            sender.sendMessage("ran: " + line);
            return deferred;
        }, PASSWORD);
        assertTrue(server.start("127.0.0.1", 0), "the listener should bind");
        return server.boundPort();
    }

    @Test
    void aCorrectPasswordIsAcceptedAndCommandsRun() throws IOException {
        int port = start(null);
        try (Socket client = new Socket("127.0.0.1", port)) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            send(out, new RconPacket(11, RconPacket.TYPE_AUTH, PASSWORD));
            assertEquals(RconPacket.TYPE_RESPONSE_VALUE, RconPacket.read(in).type(), "the empty packet first");
            RconPacket verdict = RconPacket.read(in);
            assertEquals(RconPacket.TYPE_AUTH_RESPONSE, verdict.type());
            assertEquals(11, verdict.id(), "an accepted auth echoes the client's own id");

            send(out, new RconPacket(12, RconPacket.TYPE_EXEC_COMMAND, "players"));
            RconPacket reply = RconPacket.read(in);
            assertEquals(RconPacket.TYPE_RESPONSE_VALUE, reply.type());
            assertEquals(12, reply.id());
            assertEquals("ran: players", reply.body());
            assertEquals(List.of("players"), ran);
        }
    }

    @Test
    void aWrongPasswordIsRefusedAndTheConnectionEnds() throws IOException {
        int port = start(null);
        try (Socket client = new Socket("127.0.0.1", port)) {
            InputStream in = client.getInputStream();
            send(client.getOutputStream(), new RconPacket(5, RconPacket.TYPE_AUTH, "not-the-password"));

            RconPacket.read(in); // the empty packet
            RconPacket verdict = RconPacket.read(in);
            assertEquals(RconPacket.TYPE_AUTH_RESPONSE, verdict.type());
            assertEquals(RconPacket.AUTH_FAILED_ID, verdict.id(), "-1 is how the protocol says no");
            assertNotEquals(5, verdict.id());

            assertNull(RconPacket.read(in), "and the server hangs up — a guess costs a reconnect");
        }
        assertTrue(ran.isEmpty());
    }

    @Test
    void aCommandBeforeAuthenticatingRunsNothing() throws IOException {
        int port = start(null);
        try (Socket client = new Socket("127.0.0.1", port)) {
            InputStream in = client.getInputStream();
            send(client.getOutputStream(), new RconPacket(1, RconPacket.TYPE_EXEC_COMMAND, "stop"));

            RconPacket reply = RconPacket.read(in);
            assertEquals(RconPacket.AUTH_FAILED_ID, reply.id(), "answered as an unauthenticated client");
            assertTrue(reply.body().isEmpty(), "and told nothing about why");
            assertTrue(ran.isEmpty(), "the command never reached the console");
        }
    }

    @Test
    void deferredWorkRunsOnlyAfterTheReplyIsSent() throws IOException {
        AtomicBoolean shutdownRan = new AtomicBoolean();
        int port = start(() -> shutdownRan.set(true));
        try (Socket client = new Socket("127.0.0.1", port)) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            authenticate(in, out);

            send(out, new RconPacket(9, RconPacket.TYPE_EXEC_COMMAND, "stop"));
            RconPacket reply = RconPacket.read(in);

            // The point of the deferral: over a socket, a shutdown that ran inline would take the answer
            // with it, and the administrator who typed 'stop' would see a dropped connection instead.
            assertEquals("ran: stop", reply.body());
            assertTrue(shutdownRan.get(), "…and only then does the deferred work run");
        }
    }

    @Test
    void aBlankPasswordRefusesToListenAtAll() {
        RconServer open = new RconServer((line, sender) -> null, "  ");
        assertFalse(open.start("127.0.0.1", 0), "an RCON port with no password is a remote shell");
        assertEquals(-1, open.boundPort());
    }

    private static void authenticate(InputStream in, OutputStream out) throws IOException {
        send(out, new RconPacket(1, RconPacket.TYPE_AUTH, PASSWORD));
        RconPacket.read(in);
        RconPacket.read(in);
    }

    private static void send(OutputStream out, RconPacket packet) throws IOException {
        out.write(packet.encode());
        out.flush();
    }
}
