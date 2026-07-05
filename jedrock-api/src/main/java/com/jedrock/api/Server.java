package com.jedrock.api;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.World;

import java.util.Collection;
import java.util.Optional;

/**
 * Absolute abstraction of the Minecraft server.
 * Implementations must remain as lightweight as possible.
 * No direct protocol or implementation details leak into this interface.
 */
public interface Server {

    /**
     * @return the name of this server implementation
     */
    String getName();

    /**
     * @return current server version
     */
    String getVersion();

    /**
     * Start the server (blocking or async depending on impl).
     */
    void start();

    /**
     * Gracefully stop the server.
     */
    void shutdown();

    /**
     * @return true if server is running
     */
    boolean isRunning();

    /**
     * Global lightweight event bus.
     */
    EventBus getEventBus();

    /**
     * Returns all currently connected players.
     * This should be a live or cheap view - avoid heavy copies.
     */
    Collection<Player> getPlayers();

    /**
     * Lookup a player by name or UUID (abstraction).
     */
    Optional<Player> getPlayer(String name);

    /**
     * World management - minimal.
     */
    Collection<World> getWorlds();

    Optional<World> getWorld(String name);

    /**
     * Current server tick (monotonic).
     */
    long getCurrentTick();

    /**
     * A cheap snapshot of live server health (TPS, MSPT, memory, players, uptime).
     */
    ServerStatus getStatus();
}
