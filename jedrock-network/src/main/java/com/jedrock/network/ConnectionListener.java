package com.jedrock.network;

import com.jedrock.api.player.PlayerConnection;

import java.util.UUID;

/**
 * Bridge from the network layer up to whoever owns server state (the core).
 *
 * <p>The network module deliberately knows nothing about the core: it only exposes this
 * hook. The core implements it and registers via {@link NetworkServer#setConnectionListener}.
 * Callbacks arrive on Netty I/O threads — implementations must be thread-safe.
 */
public interface ConnectionListener {

    /** A client finished the login flow and is now in PLAY state. */
    void onLogin(PlayerConnection connection, UUID uuid, String username);

    /** A connection closed. May fire for connections that never logged in. */
    void onDisconnect(PlayerConnection connection);

    /** An in-game player sent a chat message. The core relays it to everyone. */
    void onChat(PlayerConnection connection, String message);
}
