package com.jedrock.api.event.world;

import com.jedrock.api.event.Event;
import com.jedrock.api.world.World;

/**
 * Fired just before a world is written to disk — on the periodic autosave and at shutdown. The moment a
 * plugin flushes any world-tied state it wants persisted alongside the terrain. Not cancellable: the save
 * is the server keeping its promise that edits survive a restart.
 */
public final class WorldSaveEvent implements Event {

    private final World world;

    public WorldSaveEvent(World world) {
        this.world = world;
    }

    /** The world about to be saved. */
    public World getWorld() {
        return world;
    }
}
