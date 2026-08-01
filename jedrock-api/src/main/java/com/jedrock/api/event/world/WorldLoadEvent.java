package com.jedrock.api.event.world;

import com.jedrock.api.event.Event;
import com.jedrock.api.world.World;

/**
 * A world has become available — its terrain is in memory and anything may now read or edit it.
 *
 * <p>Fired for every world however it arrived: one found on disk at boot, one loaded on demand, and one
 * created moments earlier (a fresh world fires {@link WorldCreateEvent} first, then this). If you only
 * want the brand-new ones, listen for that instead; if you want "there is a world here now", this is it.
 *
 * <p>It fires <em>after</em> the world is registered and its block-change relay is wired, so a listener
 * can edit it and have players see the result. Not cancellable: by this point the world exists, and
 * refusing it would leave the server holding one it had been told to forget.
 *
 * <p>Note when this fires for the world the server starts in: before any player can join, but also before
 * plugins are loaded. A script cannot hear its own default world arrive — that one is already there by
 * the time the script runs, which is what {@code worlds.all()} is for.
 */
public final class WorldLoadEvent implements Event {

    private final World world;
    private final boolean created;

    public WorldLoadEvent(World world, boolean created) {
        this.world = world;
        this.created = created;
    }

    /** The world that is now available. */
    public World getWorld() {
        return world;
    }

    /** Whether this world was baked just now rather than read from a folder that already had one. */
    public boolean isCreated() {
        return created;
    }
}
