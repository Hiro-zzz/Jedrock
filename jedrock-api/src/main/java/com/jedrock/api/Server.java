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
import java.util.UUID;

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
     * Lookup a player by name (case-insensitive).
     */
    Optional<Player> getPlayer(String name);

    /**
     * Lookup a connected player by their unique id.
     */
    Optional<Player> getPlayer(UUID uuid);

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
     * The world players join into — the one every current implementation runs exactly one of.
     * Never {@code null}; the default returns the first of {@link #getWorlds()}.
     */
    default World getDefaultWorld() {
        return getWorlds().iterator().next();
    }

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
     * Spawn an <b>item prop</b> — a dropped-item entity whose body is {@code state} (a canonical
     * {@code (id << 4) | meta}; a block id renders as a small floating block, an item as its model).
     * The decoration primitive: unlike a real block it sits at a fractional position, hangs unsupported,
     * overlaps its neighbours and takes a floating label. Immobile and never picked up — the server
     * simulates nothing. Returns the same handle a puppet does, so it moves, turns and despawns alike.
     */
    PuppetEntity spawnItem(Location at, int state);

    /**
     * Spawn a <b>falling-block prop</b> — like {@link #spawnItem} but rendering {@code state} at
     * <em>full block size</em> rather than as a small model. Pinned against the client's own physics
     * wherever the edition allows it (JE 1.8 has no such field, so a prop may drift for those players).
     */
    PuppetEntity spawnFallingBlock(Location at, int state);

    /**
     * Spawn a <b>floating line of text</b> — a hologram line as an ordinary entity, so it moves, ticks
     * and despawns like the rest. Re-text it with {@link PuppetEntity#setNameTag}. For a block of
     * several lines, spawn one per line (a group keeps them together) or use
     * {@link #spawnHologram}, which manages the stack for you.
     */
    PuppetEntity spawnText(Location at, String text);

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
