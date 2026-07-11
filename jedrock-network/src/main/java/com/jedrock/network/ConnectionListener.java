package com.jedrock.network;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;

import java.util.List;
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

    /** A player started or stopped using an item (eat / drink / block / draw bow); the core relays it. */
    default void onUseItem(PlayerConnection connection, boolean using) {}

    /** A player swung their arm (attack / dig / interact); the core relays it to everyone else. */
    default void onSwingArm(PlayerConnection connection) {}

    /**
     * A player left-clicked (attacked) another entity. {@code targetEntityId} is the server-assigned id
     * of the victim's avatar ({@code Entity#getEntityId}); the core resolves it to a player and applies
     * melee damage. The client sends its own arm-swing separately ({@link #onSwingArm}). Fires on an I/O
     * thread. A widened id (long) so it fits both JE's int target and PE's varlong runtime id.
     */
    default void onAttack(PlayerConnection connection, long targetEntityId) {}

    /**
     * The game mode a client should join in: its last {@code /gamemode} choice this run if it has one,
     * otherwise the configured default. Queried by the join sequence (JE Join Game / PE StartGame) so a
     * returning player keeps their mode — the only way MCPE 0.14, which can't switch mode live, ever
     * changes. Called on an I/O thread before {@code onLogin}; must be cheap and thread-safe.
     */
    default GameMode gameModeFor(UUID uuid) {
        return GameMode.CREATIVE;
    }

    /**
     * The current game mode of an in-game player, by connection — lets an edition decide break timing
     * (creative mines instantly; survival breaks only when digging finishes). Defaults to creative
     * (instant) for an unknown connection, matching the pre-survival behaviour.
     */
    default GameMode gameModeOf(PlayerConnection connection) {
        return GameMode.CREATIVE;
    }

    /**
     * A player's client reported falling {@code fallDistance} blocks. In the illusionist model the
     * client is authoritative for its own physics, so it tells us (MCPE {@code EntityFall}); the core
     * turns the distance into fall damage rather than simulating gravity. Fires on an I/O thread.
     */
    default void onFall(PlayerConnection connection, float fallDistance) {}

    /** Name, help text and aliases of one in-game command — all an edition needs to advertise it. */
    record CommandInfo(String name, String description, List<String> aliases) {}

    /**
     * The in-game commands the core has registered. A Bedrock client validates a typed slash command
     * against a manifest the server sends it and silently drops anything it wasn't told about, so the
     * PE session advertises these in an {@code AvailableCommands} packet at spawn. Java clients need
     * nothing — they send {@code /…} straight through as chat.
     */
    default List<CommandInfo> commands() {
        return List.of();
    }
}
