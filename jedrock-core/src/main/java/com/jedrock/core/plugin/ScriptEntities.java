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
    private final List<ScriptEntity> entities;
    /**
     * The world this view spawns into, or {@code null} for the default one. The {@code entities} global
     * is the {@code null} view, which is why a script that has never heard of other worlds is unaffected.
     */
    private final com.jedrock.api.world.World world;
    /** The view this one was made from, or {@code null} if this IS the root the plugin owns. */
    private final ScriptEntities root;
    /** The one repeating task that drives every ticking entity of this plugin; started on first use. */
    private volatile com.jedrock.gameloop.Scheduler.Task ticker;

    ScriptEntities(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
        this.entities = new ArrayList<>();
        this.world = null;
        this.root = null;
    }

    /**
     * A view bound to another world. It shares the root's roster deliberately: a hot reload has to clear
     * every entity this plugin spawned, and one spawned through a view is no less the plugin's.
     */
    private ScriptEntities(ScriptEntities root, com.jedrock.api.world.World world) {
        this.manager = root.manager;
        this.plugin = root.plugin;
        this.entities = root.entities;
        this.world = world;
        this.root = root;
    }

    /**
     * The same {@code entities} object, pointed at another world:
     *
     * <pre>{@code
     *   const hell = entities.in('hell');
     *   hell.spawn('zombie', 0, 40, 0);        // spawns THERE, seen only by players there
     *   hell.circle(8, 0, 40, 0, 3, (x, y, z) => hell.spawnItem(lamp, x, y, z));
     * }</pre>
     *
     * <p>Everything else on the object works unchanged, so nothing had to grow a world argument. The
     * view's {@code all} / {@code near} / {@code removeAll} see only that world's entities, which is what
     * "the entities in the nether" ought to mean; the plugin's own teardown still clears them all.
     *
     * @param world a world name, or a world object from {@code worlds.get(...)}
     */
    public ScriptEntities in(Object world) {
        return new ScriptEntities(root != null ? root : this, resolveWorld(world));
    }

    /** The world this view spawns into. */
    public ScriptWorld getWorld() {
        return new ScriptWorld(manager, worldOrDefault());
    }

    private com.jedrock.api.world.World worldOrDefault() {
        return world != null ? world : manager.server().getDefaultWorld();
    }

    private com.jedrock.api.world.World resolveWorld(Object world) {
        if (world instanceof ScriptWorld view) {
            return view.unwrap();
        }
        if (world instanceof com.jedrock.api.world.World real) {
            return real;
        }
        String name = String.valueOf(world);
        return manager.server().getWorld(name).orElseThrow(
                () -> new IllegalArgumentException("no world named '" + name + "'"));
    }

    /**
     * Spawn an entity of {@code type} (case-insensitive: {@code 'zombie'}, {@code 'pig'}, {@code 'player'}…)
     * at a position — either three coordinates or a {@link Location}. Visible to every player,
     * cross-edition, and owned by this plugin.
     */
    public ScriptEntity spawn(String type, double x, double y, double z) {
        Location spawn = new Location(worldOrDefault(), x, y, z);
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
        return spawnItem(state, new Location(worldOrDefault(), x, y, z));
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
        return spawnBlock(state, new Location(worldOrDefault(), x, y, z));
    }

    /** Spawn a full-size block prop at a {@link Location}. */
    public ScriptEntity spawnBlock(int state, Location at) {
        return track(manager.server().spawnFallingBlock(at, state));
    }

    /**
     * Spawn a <b>floating line of text</b> — the label that names an exhibit. Authored in the unified
     * {@code {color}} markup, and an entity like any other: movable, tickable, owned by this plugin,
     * re-textable with {@link ScriptEntity#setNameTag}. Stack several (a {@link ScriptGroup} keeps
     * them together) for a multi-line sign.
     */
    public ScriptEntity spawnText(String text, double x, double y, double z) {
        return spawnText(text, new Location(worldOrDefault(), x, y, z));
    }

    /** Spawn a floating line of text at a {@link Location}. */
    public ScriptEntity spawnText(String text, Location at) {
        return track(manager.server().spawnText(at, text));
    }

    /**
     * A new, empty {@link ScriptGroup} — a set of entities handled as one, so a scene can be moved,
     * turned or cleared in a single call.
     */
    public ScriptGroup group() {
        return newGroup();
    }

    /** Every group knows the manager, so {@code group.save(name)} can reach the scene store. */
    private ScriptGroup newGroup() {
        ScriptGroup group = new ScriptGroup();
        group.bind(manager);
        return group;
    }

    /**
     * Stand a saved scene up in the world, or hand back the one already standing, as a group.
     *
     * <p>A saved scene belongs to the <b>server</b>, not to this plugin: it is restored at boot without
     * any script, and a hot reload doesn't take it away. So this is how a script gets hold of one it
     * wants to move or re-dress — asking twice returns the same props rather than a second copy of them.
     * An unknown name yields an empty group.
     */
    public ScriptGroup loadScene(String name) {
        ScriptGroup group = newGroup();
        for (com.jedrock.api.entity.PuppetEntity puppet : manager.loadScene(name)) {
            group.add(adopt(puppet));
        }
        return group;
    }

    /** The names of every saved scene. */
    public String[] scenes() {
        java.util.List<String> names = manager.sceneNames();
        return names.toArray(new String[0]);
    }

    /** Take a scene out of the world and forget it was saved. {@code true} if there was one. */
    public boolean removeScene(String name) {
        return manager.removeScene(name);
    }

    /**
     * Place {@code count} things evenly around a circle and return them as a group. The callback is
     * handed each position and spawns whatever belongs there, so the shape and the contents stay
     * separate concerns:
     *
     * <pre>{@code
     *   entities.circle(8, x, y, z, 1.2, (px, py, pz) => entities.spawnItem(gem, px, py, pz));
     * }</pre>
     */
    public ScriptGroup circle(int count, double cx, double cy, double cz, double radius, Function place) {
        ScriptGroup group = newGroup();
        for (int i = 0; i < Math.max(0, count); i++) {
            double angle = (Math.PI * 2 / Math.max(1, count)) * i;
            group.add(callPlace(place, cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius, i));
        }
        return group;
    }

    /** Place {@code count} things evenly along the line between two points, ends included. */
    public ScriptGroup line(int count, double x1, double y1, double z1,
                            double x2, double y2, double z2, Function place) {
        ScriptGroup group = newGroup();
        int steps = Math.max(0, count);
        for (int i = 0; i < steps; i++) {
            double t = steps == 1 ? 0 : (double) i / (steps - 1);
            group.add(callPlace(place, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, i));
        }
        return group;
    }

    /** Place a {@code columns × rows} grid on the horizontal plane, {@code spacing} blocks apart. */
    public ScriptGroup grid(int columns, int rows, double x, double y, double z, double spacing,
                            Function place) {
        ScriptGroup group = newGroup();
        int index = 0;
        for (int row = 0; row < Math.max(0, rows); row++) {
            for (int column = 0; column < Math.max(0, columns); column++) {
                group.add(callPlace(place, x + column * spacing, y, z + row * spacing, index++));
            }
        }
        return group;
    }

    /** Run a placement callback and keep the entity it returns (anything else is ignored). */
    private ScriptEntity callPlace(Function place, double x, double y, double z, int index) {
        Object spawned = manager.callPlacement(plugin, place, x, y, z, index);
        return spawned instanceof ScriptEntity entity ? entity : null;
    }

    /** Wrap a freshly spawned body and put it on this plugin's roster. */
    /**
     * Wrap a puppet this plugin did <em>not</em> spawn — a saved scene's prop, which the server owns.
     * Deliberately not tracked: a hot reload clears what this plugin spawned, and a scene standing in the
     * world is not that. It gets a script handle, not an owner.
     */
    private ScriptEntity adopt(com.jedrock.api.entity.PuppetEntity puppet) {
        return new ScriptEntity(puppet, this);
    }

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

    /** Every live entity this plugin owns — in this view's world, if it is bound to one. */
    public ScriptEntity[] all() {
        ScriptEntity[] snapshot;
        synchronized (entities) {
            snapshot = entities.toArray(new ScriptEntity[0]);
        }
        if (world == null) {
            return snapshot;
        }
        List<ScriptEntity> here = new ArrayList<>(snapshot.length);
        for (ScriptEntity entity : snapshot) {
            if (entity.getLocation().world() == world) {
                here.add(entity);
            }
        }
        return here.toArray(new ScriptEntity[0]);
    }

    /** How many this plugin owns (in this view's world, if it is bound to one). */
    public int count() {
        if (world == null) {
            synchronized (entities) {
                return entities.size();
            }
        }
        return all().length;
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
        if (root != null) {
            root.ensureTicking(); // one driver per plugin, however many world views it made
            return;
        }
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
