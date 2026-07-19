package com.jedrock.core.entity;

import com.jedrock.api.entity.Hologram;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.JedrockServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Floating lines of text — the illusion with nothing left but the caption. Each line is its own invisible
 * entity, and the whole stack hangs downwards from the hologram's location, so line 0 sits at the anchor
 * and the rest follow {@link #LINE_SPACING} apart.
 *
 * <p>Holds nothing but the text and where it hangs; the {@link JedrockServer} that owns it relays every
 * change to viewers, and each edition renders a line with whatever it can make invisible.
 */
public final class CoreHologram implements Hologram {

    /** Vertical gap between lines — roughly one line of rendered text. */
    public static final double LINE_SPACING = 0.25;

    private final UUID uuid = UUID.randomUUID();
    private final long entityId = EntityIds.next();
    private final JedrockServer server;

    private volatile World world;
    private volatile Location location;
    private volatile boolean alive = true;

    /**
     * One entity id per line, parallel to {@link #lines}. Allocated when a line first appears and released
     * with it, so re-texting an existing line never re-spawns its entity.
     */
    private volatile long[] lineIds = new long[0];
    private volatile String[] lines = new String[0];

    public CoreHologram(World world, Location location, JedrockServer server, String... lines) {
        this.world = world;
        this.location = location;
        this.server = server;
        this.lines = lines.clone();
        this.lineIds = new long[lines.length];
        for (int i = 0; i < lineIds.length; i++) {
            lineIds[i] = EntityIds.next();
        }
    }

    // ===== Entity =====

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    /**
     * The hologram's own id. Its lines carry their own ids ({@link #getLineIds()}) — this one addresses the
     * hologram as a whole (it is what {@code /hologram} lists and removes) and is never sent on the wire.
     */
    @Override
    public long getEntityId() {
        return entityId;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void setLocation(Location location) {
        this.location = location;
        if (location.world() != null) {
            this.world = location.world();
        }
    }

    @Override
    public void remove() {
        if (alive) {
            alive = false;
            server.removeHologram(this); // relays the despawn of every line
        }
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public String getType() {
        return "jedrock:hologram";
    }

    // ===== Hologram =====

    @Override
    public List<String> getLines() {
        return Collections.unmodifiableList(Arrays.asList(lines));
    }

    @Override
    public void setLines(String... lines) {
        String[] old = this.lines;
        this.lines = lines.clone();
        if (lines.length == old.length) {
            // Same shape: the line entities stay, only their text changes.
            for (int i = 0; i < lines.length; i++) {
                if (!java.util.Objects.equals(old[i], lines[i])) {
                    server.relayHologramLine(this, i);
                }
            }
            return;
        }
        // The stack changed size, so it is re-spawned wholesale — simpler than diffing, and re-texting a
        // hologram is a rare, human-scale event. Fresh ids throughout, so no id is ever despawned and
        // re-spawned in the same breath.
        long[] oldIds = this.lineIds;
        long[] freshIds = new long[lines.length];
        for (int i = 0; i < freshIds.length; i++) {
            freshIds[i] = EntityIds.next();
        }
        this.lineIds = freshIds;
        server.respawnHologram(this, oldIds);
    }

    @Override
    public void setLine(int index, String text) {
        String[] current = this.lines;
        if (index < 0 || index >= current.length) {
            throw new IndexOutOfBoundsException("No line " + index + " (hologram has " + current.length + ")");
        }
        String[] updated = current.clone();
        updated[index] = text;
        this.lines = updated;
        server.relayHologramLine(this, index);
    }

    @Override
    public void teleport(Location to) {
        setLocation(to);
        server.moveHologram(this);
    }

    /** The entity id carrying each line, parallel to {@link #getLines()}. Core-internal. */
    public long[] getLineIds() {
        return lineIds;
    }

    /** Where line {@code index} hangs: the anchor, with each further line one {@link #LINE_SPACING} lower. */
    public Location lineLocation(int index) {
        Location at = location;
        return new Location(at.world(), at.x(), at.y() - index * LINE_SPACING, at.z(), 0f, 0f);
    }
}
