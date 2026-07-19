package com.jedrock.api.event.server;

import com.jedrock.api.event.Event;

/**
 * Fired once, as the server begins shutting down and while the world and players are still alive — the
 * place a plugin flushes its own state or says goodbye. Not cancellable: shutdown is already underway.
 */
public final class ServerStopEvent implements Event {
}
