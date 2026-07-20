package com.jedrock.api;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
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
     * Global lightweight event bus — where plugin and script code registers event listeners.
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
     * Number of players currently online. Defaults to the size of {@link #getPlayers()}; an implementation
     * with a cheaper counter may override it.
     */
    default int getPlayerCount() {
        return getPlayers().size();
    }

    /**
     * Send a system message to every online player. Each connection serializes it in its own protocol, so a
     * Java and a Bedrock player see the same line — the heart of cross-platform chat. The text uses the
     * unified {@code {color}} + Markdown markup.
     */
    void broadcast(String message);

    /**
     * Run a command as {@code player}, exactly as if they had typed it in chat — the same dispatch, so
     * script- and core-registered commands both work. A leading {@code /} is optional.
     */
    void dispatchCommand(Player player, String commandLine);

    /**
     * World management - minimal.
     */
    Collection<World> getWorlds();

    Optional<World> getWorld(String name);

    /**
     * Spawn a puppet — a server-controlled visual entity (the base for mobs / NPCs / holograms) — of the
     * given {@code type} at {@code at}, visible to every player cross-edition. Returns a handle to move,
     * remove or wire an interaction to it. The server never simulates it; behaviour is driven through the
     * returned {@link PuppetEntity}. The name defaults to the type name (it matters only for an
     * {@link EntityType#PLAYER} NPC — see {@link #spawnPuppet(EntityType, Location, String)}).
     */
    default PuppetEntity spawnPuppet(EntityType type, Location at) {
        return spawnPuppet(type, at, type.canonicalName());
    }

    /**
     * As {@link #spawnPuppet(EntityType, Location)} but with an explicit {@code name} — the shown name of a
     * {@link EntityType#PLAYER} NPC (its tab / player-list entry).
     */
    PuppetEntity spawnPuppet(EntityType type, Location at, String name);

    /**
     * Spawn a hologram — floating lines of text — at {@code at}, visible to every player cross-edition.
     * The topmost line sits at {@code at}; the rest hang below it. Returns a handle to re-text, move or
     * remove it. Lines use the edition-agnostic chat markup, so one string renders the same everywhere.
     */
    Hologram spawnHologram(Location at, String... lines);

    /**
     * Current server tick (monotonic).
     */
    long getCurrentTick();

    /**
     * A cheap snapshot of live server health (TPS, MSPT, memory, players, uptime).
     */
    ServerStatus getStatus();
}
