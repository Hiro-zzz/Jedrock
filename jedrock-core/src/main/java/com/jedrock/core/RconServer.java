package com.jedrock.core;

import com.jedrock.core.rcon.RconPacket;
import com.jedrock.core.rcon.RconSender;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The console over a socket: Source RCON, the protocol every Minecraft management tool already speaks.
 *
 * <p>It adds no commands. {@link ConsoleCommands#execute} is the console surface, and this hands it an
 * {@link RconSender} instead of the terminal one — so {@code stop}, {@code say}, {@code players},
 * {@code kick} and every in-game command work identically from a remote client, and anything added to the
 * console tomorrow works here without being added twice.
 *
 * <p><b>Blocking sockets, one thread per connection, and a hard cap on them.</b> Netty is right there and
 * deliberately not used: this carries an administrator typing, not a packet stream, and a game-loop
 * transport tuned for twenty position updates a second buys nothing at that rate while entangling an
 * administrative port with the pipeline that serves players. Two threads that sleep on {@code read()} are
 * the honest shape of this, and they make the whole thing testable against a real socket.
 *
 * <p><b>What it refuses to do.</b> RCON is plaintext: the password crosses the network in the clear and so
 * does everything either side says. So it is off unless asked for, binds loopback unless told otherwise,
 * and will <em>not</em> start with a blank password however enabled it is — an open RCON port is a root
 * shell for the server. A failed auth closes the connection, as the protocol requires, which turns a
 * password guess into a reconnect and makes a brute force loud and slow rather than free.
 */
final class RconServer {

    private static final JLogger LOGGER = JLogger.getLogger("Rcon");

    /** Concurrent sessions. Administrators are few; this is a bound on what a stranger can tie up. */
    private static final int MAX_SESSIONS = 4;

    /** How long a connection may sit unauthenticated (and idle) before it is dropped. */
    private static final int SOCKET_TIMEOUT_MS = 5 * 60 * 1000;

    /**
     * The console surface this exposes — {@link ConsoleCommands#execute} in production, a stub in a test.
     * Narrowed to the one method so the socket layer can be exercised without a running server behind it.
     */
    @FunctionalInterface
    interface Commands {
        Runnable execute(String line, com.jedrock.api.command.CommandSender sender);
    }

    private final Commands console;
    private final String password;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger sessions = new AtomicInteger();
    private volatile ServerSocket socket;

    RconServer(Commands console, String password) {
        this.console = console;
        this.password = password;
    }

    /**
     * Bind and start accepting. A bind failure disables RCON and nothing else — the same rule the Bedrock
     * listeners follow, for the same reason: a management port is never worth failing a server over.
     *
     * @return whether it is now listening
     */
    boolean start(String host, int port) {
        if (password == null || password.isBlank()) {
            LOGGER.warn("RCON is enabled but rcon.password is blank — not starting it. An RCON port with no "
                    + "password is a remote console for anyone who finds it.");
            return false;
        }
        try {
            ServerSocket bound = new ServerSocket();
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(host, port));
            this.socket = bound;
            running.set(true);
            Thread acceptor = new Thread(this::acceptLoop, "Jedrock-Rcon");
            acceptor.setDaemon(true);
            acceptor.start();
            LOGGER.info("RCON listening on " + host + ":" + port
                    + (isLoopback(host) ? "" : " — NOT loopback: this protocol is plaintext, so anyone who "
                    + "can reach this port and guess the password owns the server. Prefer 127.0.0.1 and an "
                    + "SSH tunnel."));
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not bind RCON on " + host + ":" + port + " (" + e.getMessage()
                    + "); RCON is disabled for this run");
            return false;
        }
    }

    /** Stop listening. Open sessions end when their socket closes. */
    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ServerSocket open = socket;
        if (open != null) {
            try {
                open.close(); // wakes the acceptor out of accept()
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = socket.accept();
                if (sessions.get() >= MAX_SESSIONS) {
                    LOGGER.warn("Refusing an RCON connection from " + client.getRemoteSocketAddress()
                            + ": already " + MAX_SESSIONS + " sessions");
                    client.close();
                    continue;
                }
                sessions.incrementAndGet();
                Thread session = new Thread(() -> session(client), "Jedrock-Rcon-Session");
                session.setDaemon(true);
                session.start();
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("RCON accept failed: " + e);
                }
                return; // the socket was closed, or is no longer usable
            }
        }
    }

    /** One connection: authenticate once, then run commands until it goes away. */
    private void session(Socket client) {
        String who = String.valueOf(client.getRemoteSocketAddress());
        boolean authenticated = false;
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            RconPacket packet;
            while (running.get() && (packet = RconPacket.read(in)) != null) {
                if (packet.type() == RconPacket.TYPE_AUTH) {
                    authenticated = authenticate(packet, out, who);
                    if (!authenticated) {
                        return; // the protocol says a refused password ends the connection
                    }
                } else if (packet.type() == RconPacket.TYPE_EXEC_COMMAND) {
                    if (!authenticated) {
                        // Never hint at the reason: an unauthenticated client learns nothing from us.
                        write(out, new RconPacket(RconPacket.AUTH_FAILED_ID,
                                RconPacket.TYPE_RESPONSE_VALUE, ""));
                        return;
                    }
                    run(packet, out, who);
                } else {
                    write(out, new RconPacket(packet.id(), RconPacket.TYPE_RESPONSE_VALUE, ""));
                }
            }
        } catch (IOException e) {
            LOGGER.debug(() -> "RCON session " + who + " ended: " + e);
        } finally {
            sessions.decrementAndGet();
        }
    }

    private boolean authenticate(RconPacket packet, OutputStream out, String who) throws IOException {
        // Constant-time so the comparison itself says nothing about how much of the password was right.
        boolean ok = java.security.MessageDigest.isEqual(
                packet.body().getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        // Clients expect an empty RESPONSE_VALUE before the verdict; several will not read the verdict
        // without it, and it costs one packet.
        write(out, new RconPacket(ok ? packet.id() : RconPacket.AUTH_FAILED_ID,
                RconPacket.TYPE_RESPONSE_VALUE, ""));
        write(out, new RconPacket(ok ? packet.id() : RconPacket.AUTH_FAILED_ID,
                RconPacket.TYPE_AUTH_RESPONSE, ""));
        if (ok) {
            LOGGER.info("RCON authenticated: " + who);
        } else {
            LOGGER.warn("RCON authentication failed from " + who + " — connection closed");
        }
        return ok;
    }

    private void run(RconPacket packet, OutputStream out, String who) throws IOException {
        String line = packet.body().trim();
        LOGGER.info("RCON " + who + ": " + line);
        RconSender sender = new RconSender();
        Runnable deferred = null;
        try {
            deferred = console.execute(line, sender);
        } catch (RuntimeException e) {
            LOGGER.error("RCON command failed: " + line, e);
            sender.sendMessage("command failed: " + e);
        }
        for (RconPacket reply : split(packet.id(), sender.output())) {
            write(out, reply);
        }
        out.flush();
        if (deferred != null) {
            deferred.run(); // e.g. stop — only now that the answer is on the wire
        }
    }

    /**
     * Cut a reply into packets the protocol can carry. A body has a 4 KiB ceiling and a long
     * {@code players} listing on a full server will find it; the pieces arrive in order and every client
     * concatenates them, so the split is invisible.
     */
    private static java.util.List<RconPacket> split(int id, String body) {
        java.util.List<RconPacket> out = new java.util.ArrayList<>();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= RconPacket.MAX_BODY_BYTES) {
            out.add(new RconPacket(id, RconPacket.TYPE_RESPONSE_VALUE, body));
            return out;
        }
        int from = 0;
        while (from < bytes.length) {
            int take = Math.min(RconPacket.MAX_BODY_BYTES, bytes.length - from);
            // Don't split a multi-byte character down the middle: back off to a clean boundary.
            while (take > 1 && from + take < bytes.length && (bytes[from + take] & 0xC0) == 0x80) {
                take--;
            }
            out.add(new RconPacket(id, RconPacket.TYPE_RESPONSE_VALUE,
                    new String(bytes, from, take, StandardCharsets.UTF_8)));
            from += take;
        }
        return out;
    }

    private static void write(OutputStream out, RconPacket packet) throws IOException {
        out.write(packet.encode());
        out.flush();
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("::1") || host.equalsIgnoreCase("localhost");
    }

    /** The port actually bound — for tests, which ask for port 0 and need to know what they got. */
    int boundPort() {
        ServerSocket open = socket;
        return open == null ? -1 : open.getLocalPort();
    }
}
