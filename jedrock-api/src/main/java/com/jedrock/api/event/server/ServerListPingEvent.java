package com.jedrock.api.event.server;

import com.jedrock.api.ServerPing;
import com.jedrock.api.event.Event;

/**
 * Somebody's client is refreshing its multiplayer list and this server is answering.
 *
 * <p>Everything a listener may change is on the {@link ServerPing} — the MOTD, the two numbers beside it —
 * and changing it there is what the client will see. A rotating message, a maintenance notice, a count
 * that hides staff, a different line for an old client: all of it is one line in a handler.
 *
 * <p>Fires for <b>both</b> editions, from the Java status request and the Bedrock query alike, so one
 * listener covers a server that is listening on three sockets. What the two do with the answer differs in
 * ways nothing here can hide: Bedrock's list is a single semicolon-joined line with its own second title
 * from the config, and the Java client renders the MOTD as a chat component. Keep the message plain enough
 * to read on both.
 *
 * <p>Not cancellable. Refusing to answer a ping is not "the server says no", it is "the server is down" —
 * and a server that is up and pretending otherwise is a worse thing to build than a rude MOTD.
 *
 * <p>This runs on a network I/O thread, before any player state exists, and is the one event here with no
 * player at all.
 */
public final class ServerListPingEvent implements Event {

    private final ServerPing ping;

    public ServerListPingEvent(ServerPing ping) {
        this.ping = ping;
    }

    /** The answer being assembled. Change it in place. */
    public ServerPing getPing() {
        return ping;
    }
}
