package com.jedrock.core.plugin;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import org.mozilla.javascript.Function;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The {@code entities} object a script sees — spawn and find script-driven entities. One per plugin, so
 * every entity a script spawns is owned by it and removed when the plugin is unloaded or hot-reloaded:
 * a reloaded script starts from a clean world instead of leaving orphaned bodies behind with dead
 * callbacks.
 *
 * <pre>{@code
 *   const pig = entities.spawn('pig', x, y, z);   // or entities.spawn('pig', someLocation)
 *   entities.all().length;                        // this plugin's entities
 *   entities.near(x, y, z, 10);                   // …within 10 blocks
 *   entities.removeAll();
 * }</pre>
 *
 * Entities are the illusionist's mobs: bodies the server renders and relays cross-edition but never
 * simulates. Behaviour is whatever the script writes in {@link ScriptEntity#onTick}.
 */
public final class ScriptEntities {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final List<ScriptEntity> entities = new ArrayList<>();
    /** The one repeating task that drives every ticking entity of this plugin; started on first use. */
    private volatile com.jedrock.gameloop.Scheduler.Task ticker;

    ScriptEntities(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /**
     * Spawn an entity of {@code type} (case-insensitive: {@code 'zombie'}, {@code 'pig'}, {@code 'player'}…)
     * at a position — either three coordinates or a {@link Location}. Visible to every player,
     * cross-edition, and owned by this plugin.
     */
    public ScriptEntity spawn(String type, double x, double y, double z) {
        Location spawn = new Location(manager.server().getDefaultWorld(), x, y, z);
        return spawnAt(type, spawn, null);
    }

    /** Spawn at a {@link Location} (keeping its facing). */
    public ScriptEntity spawn(String type, Location at) {
        return spawnAt(type, at, null);
    }

    /**
     * Spawn with an explicit name. It matters for a {@code 'player'} entity — that is the NPC's shown
     * name; for a mob the floating text is its {@link ScriptEntity#setNameTag name tag} instead.
     */
    public ScriptEntity spawn(String type, Location at, String name) {
        return spawnAt(type, at, name);
    }

    /**
     * Spawn an <b>item prop</b> — a body that is an item or a block, the decoration primitive. Pass a
     * canonical state: {@code Blocks.state(35, 14)} for red wool, or a bare item id shifted the same way.
     * Unlike a real block it can sit at a fractional position, hang in mid-air and overlap its
     * neighbours; it never falls and is never picked up. Otherwise it is an entity like any other —
     * movable, labelable, tickable.
     *
     * <pre>{@code
     *   const lamp = entities.spawnItem(Blocks.state(89, 0), x, y + 2.5, z);  // floating glowstone
     *   lamp.setNameTag('{yellow}Lantern');
     * }</pre>
     */
    public ScriptEntity spawnItem(int state, double x, double y, double z) {
        return spawnItem(state, new Location(manager.server().getDefaultWorld(), x, y, z));
    }

    /** Spawn an item prop at a {@link Location}. */
    public ScriptEntity spawnItem(int state, Location at) {
        return track(manager.server().spawnItem(at, state));
    }

    /**
     * Spawn a <b>full-size block</b> prop — the same idea as {@link #spawnItem} but rendered at block
     * scale instead of as a small model, so it reads as architecture rather than as a dropped thing.
     * Pinned in place wherever the edition allows it (a JE 1.8 client has no such lever, so it may
     * drift there).
     */
    public ScriptEntity spawnBlock(int state, double x, double y, double z) {
        return spawnBlock(state, new Location(manager.server().getDefaultWorld(), x, y, z));
    }

    /** Spawn a full-size block prop at a {@link Location}. */
    public ScriptEntity spawnBlock(int state, Location at) {
        return track(manager.server().spawnFallingBlock(at, state));
    }

    /** Wrap a freshly spawned body and put it on this plugin's roster. */
    private ScriptEntity track(com.jedrock.api.entity.PuppetEntity puppet) {
        ScriptEntity entity = new ScriptEntity(puppet, this);
        synchronized (entities) {
            entities.add(entity);
        }
        plugin.addEntity(entity);
        return entity;
    }

    private ScriptEntity spawnAt(String type, Location at, String name) {
        EntityType entityType = parseType(type);
        PuppetEntity puppet = name == null
                ? manager.server().spawnPuppet(entityType, at)
                : manager.server().spawnPuppet(entityType, at, name);
        return track(puppet);
    }

    /** Every live entity this plugin owns. */
    public ScriptEntity[] all() {
        synchronized (entities) {
            return entities.toArray(new ScriptEntity[0]);
        }
    }

    /** How many this plugin owns. */
    public int count() {
        synchronized (entities) {
            return entities.size();
        }
    }

    /** This plugin's entities within {@code radius} blocks of a point. */
    public ScriptEntity[] near(double x, double y, double z, double radius) {
        double limit = radius * radius;
        List<ScriptEntity> found = new ArrayList<>();
        for (ScriptEntity entity : all()) {
            Location at = entity.getLocation();
            double dx = at.x() - x, dy = at.y() - y, dz = at.z() - z;
            if (dx * dx + dy * dy + dz * dz <= limit) {
                found.add(entity);
            }
        }
        return found.toArray(new ScriptEntity[0]);
    }

    /** Remove every entity this plugin owns (teardown does this too). */
    public void removeAll() {
        for (ScriptEntity entity : all()) {
            entity.remove();
        }
    }

    // ===== Wiring used by ScriptEntity =====

    /** The nearest online player to {@code from} within {@code radius}, or null. */
    Player nearestPlayer(Location from, double radius) {
        double limit = radius * radius;
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : manager.server().getPlayers()) {
            Location at = player.getLocation();
            double dx = at.x() - from.x(), dy = at.y() - from.y(), dz = at.z() - from.z();
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared <= limit && squared < best) {
                best = squared;
                nearest = player;
            }
        }
        return nearest;
    }

    /** Route a puppet interaction into the script, under the script lock like every other callback. */
    void bindInteract(ScriptEntity entity, Function fn) {
        if (fn == null) {
            entity.puppet().onInteract(null);
            return;
        }
        entity.puppet().onInteract(player ->
                manager.callEntityHandler(plugin, fn, entity, player));
    }

    /** Drop an entity from this plugin's roster (it was removed). */
    void forget(ScriptEntity entity) {
        synchronized (entities) {
            entities.remove(entity);
        }
        plugin.removeEntity(entity);
    }

    /**
     * Start the shared per-tick driver on the first {@link ScriptEntity#onTick}. One task for the whole
     * plugin rather than one per entity: the loop is cheap, and this way a hundred entities cost one
     * scheduled task. It is tracked on the plugin, so teardown cancels it like any other task.
     */
    synchronized void ensureTicking() {
        if (ticker != null) {
            return;
        }
        ticker = manager.scheduler().runTaskTimer(this::tickAll, 1L, 1L);
        plugin.addTask(ticker);
    }

    /** Fire every ticking entity's handler once. Runs on the game-loop thread. */
    private void tickAll() {
        for (ScriptEntity entity : all()) {
            Function handler = entity.tickHandler();
            if (handler != null && entity.isAlive()) {
                manager.callEntityHandler(plugin, handler, entity, null);
            }
        }
    }

    private static EntityType parseType(String name) {
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown entity type '" + name + "' — one of: "
                    + Arrays.toString(EntityType.values()));
        }
    }
}
