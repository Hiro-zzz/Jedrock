package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.World;
import com.jedrock.core.player.CorePlayer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Wrapper;
import org.mozilla.javascript.WrapFactory;

/**
 * The gate every Java object passes through on its way into a script — and the only place that can close
 * the gap between "what the api promises" and "what a plugin can actually call".
 *
 * <p>Rhino reflects an object's <b>runtime class</b>. Declaring a field as the api interface changes
 * nothing, and neither does Rhino's own {@code staticType} argument, which only narrows the surface when
 * reflection outright fails (confirmed by experiment before this was written). So for as long as the core
 * handed scripts its own objects, a plugin could call anything those objects happened to expose —
 * {@code player.getConnection()} into the network layer, {@code server.getOpList()} into the permission
 * store — with the {@code api} module describing none of it and nothing failing to compile when an
 * internal was renamed.
 *
 * <p>Substituting a wrapper here fixes every path at once, because Rhino routes <em>all</em> of them
 * through a WrapFactory: the globals, an event's {@code getPlayer()}, a command argument, the result of
 * {@code server.getPlayers()}, an entity's nearest-player query. There is no way to obtain a core object
 * in JavaScript that does not come through this method.
 *
 * <p>Only the types with a real script contract are substituted; everything else (api events, enums,
 * {@code Location}, the {@code Script*} globals themselves) is passed through untouched.
 */
final class ScriptWrapFactory extends WrapFactory {

    /** One view per server and per world — both are singletons in practice, so this never grows. */
    private final java.util.Map<Server, ScriptServer> servers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<World, ScriptWorld> worlds = new java.util.concurrent.ConcurrentHashMap<>();

    private final PluginManager manager;

    ScriptWrapFactory(PluginManager manager) {
        this.manager = manager;
    }

    @Override
    public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class<?> staticType) {
        Object contract = contractFor(javaObject, scope);
        if (contract != javaObject) {
            return super.wrapAsJavaObject(cx, scope, contract, contract.getClass());
        }
        return super.wrapAsJavaObject(cx, scope, javaObject, staticType);
    }

    /**
     * The script-facing stand-in for a core object, or the object itself when it is already contract.
     *
     * <p>The same object gets the same view every time, and that is not an optimisation. Rhino compares
     * two Java objects with {@code ==} by unwrapping both and comparing <em>references</em> — so a fresh
     * view per crossing would quietly make {@code e.getPlayer() == watched} false, and a script that
     * works today would start ignoring the very player it is watching. A player's view is kept on the
     * player, so it dies with them rather than in a map of everyone who ever logged in.
     */
    private Object contractFor(Object javaObject, Scriptable scope) {
        if (javaObject instanceof Hologram hologram) {
            return new ScriptHologram(hologram);
        }
        if (javaObject instanceof PuppetEntity puppet) {
            // A puppet's callback belongs to whichever plugin is asking, so it can die with that plugin
            // instead of firing into a scope that was thrown away on reload.
            return new ScriptPuppet(manager, manager.pluginForScope(scope), puppet);
        }
        if (javaObject instanceof CorePlayer core) {
            ScriptPlayer view = core.scriptView();
            if (view == null) {
                view = new ScriptPlayer(core);
                core.scriptView(view);
            }
            return view;
        }
        if (javaObject instanceof Player player) {
            return new ScriptPlayer(player);   // a foreign Player implementation: no place to cache it
        }
        if (javaObject instanceof Server server) {
            return servers.computeIfAbsent(server, ScriptServer::new);
        }
        if (javaObject instanceof World world) {
            return worlds.computeIfAbsent(world, w -> new ScriptWorld(manager, w));
        }
        return javaObject;
    }

    /**
     * Turn whatever a script passed as "a player" back into the api {@link Player} — a
     * {@link ScriptPlayer}, a still-raw player, or a Rhino wrapper around either. Returns {@code null}
     * for anything else, so a caller can refuse it rather than throw a class-cast at the script.
     */
    static Player unwrapPlayer(Object value) {
        Object unwrapped = value instanceof Wrapper w ? w.unwrap() : value;
        if (unwrapped instanceof ScriptPlayer sp) {
            return sp.unwrap();
        }
        return unwrapped instanceof Player p ? p : null;
    }
}
