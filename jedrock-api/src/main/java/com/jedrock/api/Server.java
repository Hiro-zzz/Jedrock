package com.jedrock.api;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.api.world.WorldTemplate;

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
     * Every world currently loaded — an overworld, a nether, or several of each. Each is a separate
     * finite world with its own terrain, chests, spawn and weather; a player stands in exactly one.
     */
    Collection<World> getWorlds();

    Optional<World> getWorld(String name);

    /**
     * The world players join into. Never {@code null}; the default returns the first of
     * {@link #getWorlds()}.
     */
    default World getDefaultWorld() {
        return getWorlds().iterator().next();
    }

    /**
     * Create a world from a named {@link WorldTemplate} and bring it up — baked on the spot if it is new
     * (which blocks the calling thread for as long as the bake takes: a few seconds for the default
     * size), loaded from its folder if it has one. The world is live when this returns: players can be
     * teleported into it, scripts can edit it, and it saves with every other world.
     *
     * @param name     the world's name, which is also its folder next to the process. Letters, digits,
     *                 {@code _} and {@code -} only
     * @param template the name of a registered template ({@code overworld}, {@code nether},
     *                 {@code overworld_small}, {@code nether_small}, {@code bare}, or one a script
     *                 registered)
     * @param seed     the seed to grow it from, or {@code null} for the template's own (or a random one)
     * @throws IllegalArgumentException if the name is unusable or no such template is registered
     * @throws IllegalStateException    if a world by that name already exists
     */
    World createWorld(String name, String template, Long seed);

    /** As {@link #createWorld(String, String, Long)} with the template's own seed. */
    default World createWorld(String name, String template) {
        return createWorld(name, template, null);
    }

    /**
     * Take a world out of memory, saving it first. Its folder stays on disk, so it comes back at the
     * next boot (and {@link #createWorld} will refuse the name until the folder is gone). Refused for
     * the default world and for one that still has players in it — move them out first.
     *
     * @return {@code true} if it was unloaded
     */
    boolean unloadWorld(String name);

    /** Every registered world template, built-ins first. */
    Collection<WorldTemplate> getWorldTemplates();

    /**
     * Register (or replace) a named template, so {@link #createWorld} can build from it. A script that
     * wants "my arena world" declares it once at load and creates by name from then on.
     */
    void registerWorldTemplate(WorldTemplate template);

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
