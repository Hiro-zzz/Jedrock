package com.jedrock.api.event.world;

import com.jedrock.api.event.Event;
import com.jedrock.api.world.World;

/**
 * A world has been baked from a template for the first time — the one moment in a world's life when its
 * terrain is new. It fires once, ever, for that world: on every later boot the same folder comes back
 * through {@link WorldLoadEvent} alone.
 *
 * <p>Which is what makes this the place for anything a world should be born with — a spawn platform, an
 * arena carved out, a starting structure. Doing that on load instead would re-cut it over whatever
 * players had since built there.
 *
 * <p>Order: this, then {@link WorldLoadEvent} for the same world. Not cancellable — by the time the
 * terrain is baked and on disk there is nothing left to refuse.
 */
public final class WorldCreateEvent implements Event {

    private final World world;
    private final String template;

    public WorldCreateEvent(World world, String template) {
        this.world = world;
        this.template = template;
    }

    /** The world that has just been made. */
    public World getWorld() {
        return world;
    }

    /** The name of the template it was made from — {@code 'overworld'}, {@code 'nether_small'}, yours. */
    public String getTemplate() {
        return template;
    }
}
