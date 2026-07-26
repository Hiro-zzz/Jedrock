package com.jedrock.core.plugin;

import com.jedrock.api.entity.Hologram;
import com.jedrock.api.world.Location;

import java.util.List;

/**
 * A hologram as scripts see it — the managed stack of floating lines {@code server.spawnHologram(...)}
 * returns. Purely text and a position: no callbacks, so unlike {@link ScriptPuppet} it needs nothing from
 * the plugin that holds it.
 *
 * <p>It exists for the same reason as the others (see {@link ScriptWrapFactory}): the implementation
 * behind it carries the per-line entity ids and the spacing arithmetic that place each line on the wire,
 * and none of that is a plugin's business.
 */
public final class ScriptHologram {

    private final Hologram hologram;

    ScriptHologram(Hologram hologram) {
        this.hologram = hologram;
    }

    /** The lines as they are shown now, top to bottom. */
    public List<String> getLines() {
        return hologram.getLines();
    }

    /** Replace every line. The stack regrows downward from the hologram's position. */
    public void setLines(String... lines) {
        hologram.setLines(lines);
    }

    /** Rewrite one line in place, leaving the rest — markup renders, as everywhere else. */
    public void setLine(int index, String text) {
        hologram.setLine(index, text);
    }

    public Location getLocation() {
        return hologram.getLocation();
    }

    public void teleport(Location to) {
        hologram.teleport(to);
    }

    public void moveTo(double x, double y, double z) {
        Location at = hologram.getLocation();
        hologram.teleport(new Location(at.world(), x, y, z, at.yaw(), at.pitch()));
    }

    public boolean isAlive() {
        return hologram.isAlive();
    }

    /** Take the whole stack out of the world. */
    public void remove() {
        hologram.remove();
    }

    @Override
    public String toString() {
        return "Hologram(" + hologram.getLines().size() + " lines)";
    }
}
