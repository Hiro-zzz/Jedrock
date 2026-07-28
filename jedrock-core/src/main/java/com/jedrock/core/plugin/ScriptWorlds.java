package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.api.world.WorldTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code worlds} global: every world the server has, and the two verbs that matter — make one, walk
 * into one.
 *
 * <pre>
 *   var hell = worlds.create('hell', 'nether');       // baked on the spot, from a template
 *   worlds.send(player, 'hell');                      // …and in they go
 *   worlds.get('hell').setBlock(0, 40, 0, 89, 0);     // an ordinary world global, pointed elsewhere
 * </pre>
 *
 * <p>Each world is handed back as the same {@link ScriptWorld} the {@code world} global is, so
 * everything a script already knows how to do to the world it starts in works on any of them. The
 * {@code world} global itself stays the default world — a script that has never heard of this one keeps
 * working exactly as it did.
 *
 * <p>Creating a world <b>bakes it</b>, which blocks the calling thread for a few seconds at the default
 * size. Do it at load, not inside an event.
 */
public final class ScriptWorlds {

    private final Server server;
    private final PluginManager manager;

    ScriptWorlds(Server server, PluginManager manager) {
        this.server = server;
        this.manager = manager;
    }

    /** Every loaded world, the default first. */
    public ScriptWorld[] all() {
        List<ScriptWorld> out = new ArrayList<>();
        for (World world : server.getWorlds()) {
            out.add(new ScriptWorld(manager, world));
        }
        return out.toArray(new ScriptWorld[0]);
    }

    /** A world by name (case-insensitive), or {@code null}. */
    public ScriptWorld get(String name) {
        return server.getWorld(name).map(w -> new ScriptWorld(manager, w)).orElse(null);
    }

    /** The world players join into. */
    public ScriptWorld getDefault() {
        return new ScriptWorld(manager, server.getDefaultWorld());
    }

    /** The names of every loaded world. */
    public String[] names() {
        List<String> out = new ArrayList<>();
        for (World world : server.getWorlds()) {
            out.add(world.getName());
        }
        return out.toArray(new String[0]);
    }

    /** Whether a world by this name is loaded. */
    public boolean exists(String name) {
        return server.getWorld(name).isPresent();
    }

    /** {@code 'overworld'} or {@code 'nether'} — what kind of world this one is. */
    public String kindOf(String name) {
        World world = server.getWorld(name).orElse(null);
        return world == null ? null : world.getDimension().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Create a world from a named template and bake it. Blocks while it bakes.
     *
     * @param name     the world's name, which is also its folder: letters, digits, {@code _} and {@code -}
     * @param template {@code 'overworld'}, {@code 'nether'}, {@code 'overworld_small'},
     *                 {@code 'nether_small'}, {@code 'bare'}, or one {@link #defineTemplate} registered
     */
    public ScriptWorld create(String name, String template) {
        return new ScriptWorld(manager, server.createWorld(name, template, null));
    }

    /** As {@link #create}, from an explicit seed — the same seed always grows the same world. */
    public ScriptWorld create(String name, String template, double seed) {
        return new ScriptWorld(manager, server.createWorld(name, template, (long) seed));
    }

    /** Load it if it is already there, create it from the template if it isn't — what a script wants at load. */
    public ScriptWorld getOrCreate(String name, String template) {
        ScriptWorld existing = get(name);
        return existing != null ? existing : create(name, template);
    }

    /**
     * Save a world and take it out of memory. Its folder stays, so it returns at the next boot. Refused
     * for the default world and for one with players still in it.
     */
    public boolean unload(String name) {
        return server.unloadWorld(name);
    }

    /**
     * Register a template of your own, so {@link #create} can build from it — the "по шаблону" half of
     * worlds. A template is a recipe, not a saved world: two worlds built from one share its rules and
     * nothing else.
     *
     * @param name      the template's name
     * @param kind      {@code 'overworld'} or {@code 'nether'}
     * @param size      extent in chunks per side (2..96)
     * @param decorate  whether to run the decoration passes (trees / lakes / caves, glowstone / ore)
     */
    public void defineTemplate(String name, String kind, int size, boolean decorate) {
        server.registerWorldTemplate(new WorldTemplate(name, dimensionOf(kind), size, decorate, null));
    }

    /** As {@link #defineTemplate}, pinned to one seed so every world built from it is identical. */
    public void defineTemplate(String name, String kind, int size, boolean decorate, double seed) {
        server.registerWorldTemplate(
                new WorldTemplate(name, dimensionOf(kind), size, decorate, (long) seed));
    }

    /** The names of every registered template. */
    public String[] templates() {
        List<String> out = new ArrayList<>();
        for (WorldTemplate t : server.getWorldTemplates()) {
            out.add(t.name());
        }
        return out.toArray(new String[0]);
    }

    /**
     * Send a player to another world, arriving at its spawn. The journey goes through the same path a
     * {@code /world tp} does, so {@code PlayerTeleport} and {@code PlayerWorldChange} both fire and
     * either may refuse it.
     *
     * @return {@code false} if there is no such world, or a listener refused
     */
    public boolean send(Object player, String worldName) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        World world = server.getWorld(worldName).orElse(null);
        if (target == null || world == null) {
            return false;
        }
        return sendTo(target, world.getSpawnLocation());
    }

    /** As {@link #send}, arriving at a chosen point in that world. */
    public boolean sendTo(Object player, String worldName, double x, double y, double z) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        World world = server.getWorld(worldName).orElse(null);
        if (target == null || world == null) {
            return false;
        }
        return sendTo(target, new Location(world, x, y, z));
    }

    private boolean sendTo(Player target, Location to) {
        target.teleport(to); // the server's teleport, installed on every registered player
        return target.getWorld() == to.world();
    }

    /** The world a player is standing in. */
    public ScriptWorld of(Object player) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("worlds.of expects a player");
        }
        return new ScriptWorld(manager, target.getWorld());
    }

    private static Dimension dimensionOf(String kind) {
        if (kind == null) {
            throw new IllegalArgumentException("world kind must be 'overworld' or 'nether'");
        }
        return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
            case "overworld", "normal" -> Dimension.OVERWORLD;
            case "nether", "hell" -> Dimension.NETHER;
            default -> throw new IllegalArgumentException(
                    "unknown world kind '" + kind + "' — use 'overworld' or 'nether'");
        };
    }
}
