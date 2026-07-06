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

    /**
     * An in-game player reported a new position/look (client-authoritative movement).
     * Fires at the client's own rate (~20/s while moving) — keep implementations lean.
     * {@code y} is the feet position in both editions.
     */
    void onMove(PlayerConnection connection, double x, double y, double z, float yaw, float pitch);

    /**
     * An in-game player edited a block (break or place). The core applies it to the shared world
     * and relays it to everyone. {@code state} is the new canonical block state {@code (id << 4) |
     * meta} (0 = air = a break).
     */
    void onBlockChange(PlayerConnection connection, int x, int y, int z, int state);

    /**
     * Current number of online players, for the server-list ping (JE status + PE query). Queried
     * on I/O threads before login, so it must be cheap and thread-safe. Defaults to 0.
     */
    default int getOnlinePlayerCount() {
        return 0;
    }

    /** A player started or stopped sneaking (crouch pose); the core relays it to everyone else. */
    default void onSneak(PlayerConnection connection, boolean sneaking) {}

    /** A player started or stopped sprinting; the core relays it to everyone else. */
    default void onSprint(PlayerConnection connection, boolean sprinting) {}

    /** A player swung their arm (attack / dig / interact); the core relays it to everyone else. */
    default void onSwingArm(PlayerConnection connection) {}
}
