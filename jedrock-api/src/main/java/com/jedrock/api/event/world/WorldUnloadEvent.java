package com.jedrock.api.event.world;

import com.jedrock.api.event.Cancellable;
import com.jedrock.api.event.Event;
import com.jedrock.api.world.World;

/**
 * A world is about to be taken out of memory. Fired before anything is torn down, and the last chance to
 * read it — after this the terrain is gone from memory and only the folder remains.
 *
 * <p><b>Cancellable</b>, which is the point of announcing it at all: a script that has state tied to a
 * world can refuse to let it go. The server already refuses on its own account — the default world never
 * unloads, and neither does one with a player standing in it — and this is the same veto offered to
 * whoever else has a stake.
 *
 * <p>Unloading is not deleting. The folder and its level file stay exactly where they are, and the world
 * comes back on the next request for it.
 */
public final class WorldUnloadEvent implements Event, Cancellable {

    private final World world;
    private boolean cancelled;

    public WorldUnloadEvent(World world) {
        this.world = world;
    }

    /** The world about to be unloaded. */
    public World getWorld() {
        return world;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
