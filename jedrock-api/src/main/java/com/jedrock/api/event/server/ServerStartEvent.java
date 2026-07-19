package com.jedrock.api.event.server;

import com.jedrock.api.event.Event;

/**
 * Fired once, after the server has finished starting up (listeners bound, world ready, loop running). The
 * place a plugin does its one-time setup — register listeners, spawn its holograms, start its schedules.
 * Not cancellable.
 */
public final class ServerStartEvent implements Event {
}
