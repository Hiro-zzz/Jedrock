package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.Collection;
import java.util.UUID;

/**
 * The {@code server} object a script sees — the roster, the clock, and the things a plugin may ask the
 * server to do. Same reasoning as {@link ScriptPlayer}: Rhino reflects the runtime class, so handing a
 * script the real server handed it every public method the implementation happened to have —
 * {@code getOpList()}, {@code getNetworkServer()}, {@code getPlugins()}, the lot. None of that is api,
 * none of it was ever meant for a plugin, and nothing described the difference.
 *
 * <p>Two members of the api {@code Server} interface are left out on purpose rather than by oversight:
 * {@code start()} and {@code getEventBus()}. Starting an already-running server is meaningless, and the
 * bus is what the per-plugin {@code events} global exists to be — reaching the raw one would let a
 * script register listeners that survive its own reload.
 */
public final class ScriptServer {

    private final Server server;

    ScriptServer(Server server) {
        this.server = server;
    }

    // ===== Identity =====

    public String getName() {
        return server.getName();
    }

    public String getVersion() {
        return server.getVersion();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    /** Ticks since the server started — the clock a scheduled task counts in. */
    public long getCurrentTick() {
        return server.getCurrentTick();
    }

    /** Live TPS, MSPT, uptime and memory. */
    public ServerStatus getStatus() {
        return server.getStatus();
    }

    // ===== Players =====

    /** Every online player, on every edition. */
    public Collection<Player> getPlayers() {
        return server.getPlayers();   // the factory wraps each one as it crosses into the script
    }

    public int getPlayerCount() {
        return server.getPlayerCount();
    }

    /** The online player with this name, or {@code null} — a script reads null, not an Optional. */
    public Player getPlayer(String name) {
        return server.getPlayer(name).orElse(null);
    }

    public Player getPlayer(UUID uuid) {
        return server.getPlayer(uuid).orElse(null);
    }

    // ===== World =====

    public World getDefaultWorld() {
        return server.getDefaultWorld();
    }

    public World getWorld(String name) {
        return server.getWorld(name).orElse(null);
    }

    // ===== Doing things =====

    /** Send a system line to every online player. */
    public void broadcast(String message) {
        server.broadcast(message);
    }

    /** Run a command line as {@code player} — their permissions apply, exactly as if they typed it. */
    public void dispatchCommand(Object player, String commandLine) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target != null) {
            server.dispatchCommand(target, commandLine);
        }
    }

    // ===== Server-owned visuals (a plugin's own live in the `entities` global) =====

    /**
     * Spawn a puppet the <em>server</em> owns: unlike {@code entities.spawn(...)} it is not tied to this
     * plugin and so survives a hot reload — which also means nothing despawns it but a {@code remove()}.
     */
    public PuppetEntity spawnPuppet(EntityType type, Location at) {
        return server.spawnPuppet(type, at, null);
    }

    public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
        return server.spawnPuppet(type, at, name);
    }

    /** A stack of floating text lines, managed as one. Server-owned, like {@link #spawnPuppet}. */
    public Hologram spawnHologram(Location at, String... lines) {
        return server.spawnHologram(at, lines);
    }

    @Override
    public String toString() {
        return "Server(" + server.getName() + ")";
    }
}
