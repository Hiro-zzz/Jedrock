package com.jedrock.core.plugin;

import com.jedrock.api.world.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of entities handled as one — a scene. Decoration is rarely a single prop: it is a lantern, its
 * label and the ring of gems under it, and what you want then is to nudge the whole thing two blocks
 * left, spin it to face the path, or clear it away in one call.
 *
 * <pre>{@code
 *   const scene = entities.group();
 *   scene.add(entities.spawnBlock(Blocks.state(89, 0), x, y + 2, z));
 *   scene.add(entities.spawnText('{yellow}Lantern', x, y + 2.8, z));
 *
 *   scene.move(0, 0.5, 0);   // lift it half a block
 *   scene.rotate(45);        // spin it around its own centre
 *   scene.remove();          // and away
 * }</pre>
 *
 * A group is a view, not an owner: the entities still belong to the plugin that spawned them, so a
 * hot-reload clears them whether or not they were ever grouped. Removing a group removes its members;
 * dropping the group without removing it just forgets the arrangement.
 */
public final class ScriptGroup {

    private final List<ScriptEntity> members = new ArrayList<>();

    /** Only needed by {@link #save}; a group built before the manager was known just can't save. */
    private PluginManager manager;

    void bind(PluginManager manager) {
        this.manager = manager;
    }

    /**
     * Freeze this arrangement under {@code name} so it survives a restart — the props come back at the
     * next boot with no script involved, which is the difference between decoration and a demo.
     *
     * <p>What is saved is how the props <em>look</em>: type, position, facing, name tag, held item, armor
     * and flags. Not behaviour — an {@code onTick} brain belongs to the plugin that wrote it, and a saved
     * scene has no plugin. The entities in this group are unaffected and still belong to whoever spawned
     * them; the scene is a copy taken at this moment, and the server owns it from here.
     */
    public void save(String name) {
        if (manager == null || name == null || name.isEmpty()) {
            return;
        }
        List<com.jedrock.api.entity.PuppetEntity> props = new ArrayList<>();
        for (ScriptEntity entity : all()) {
            props.add(entity.puppet());
        }
        manager.saveScene(name, props);
    }
    /** The point moves and rotations are measured from; the centre of the members unless set. */
    private volatile Location pivot;

    ScriptGroup() {}

    /** Put an entity in the group and return it, so a spawn can be wrapped inline. */
    public ScriptEntity add(ScriptEntity entity) {
        if (entity != null) {
            synchronized (members) {
                members.add(entity);
            }
        }
        return entity;
    }

    /** How many entities are in the group (removed ones are dropped as they're noticed). */
    public int size() {
        return all().length;
    }

    /** The live members, minus any that have since been removed. */
    public ScriptEntity[] all() {
        synchronized (members) {
            members.removeIf(e -> !e.isAlive());
            return members.toArray(new ScriptEntity[0]);
        }
    }

    /**
     * The point rotations turn around and {@link #moveTo} measures from. Defaults to the centre of the
     * members at the time it is first needed.
     */
    public void setPivot(double x, double y, double z) {
        ScriptEntity[] all = all();
        Location world = all.length > 0 ? all[0].getLocation() : null;
        this.pivot = new Location(world == null ? null : world.world(), x, y, z);
    }

    /** The current pivot — the members' centre if none was set. */
    public Location getPivot() {
        Location set = pivot;
        return set != null ? set : centre();
    }

    /** Shift every member by the same offset (the pivot travels with them). */
    public void move(double dx, double dy, double dz) {
        for (ScriptEntity entity : all()) {
            Location at = entity.getLocation();
            entity.moveTo(at.x() + dx, at.y() + dy, at.z() + dz);
        }
        Location p = pivot;
        if (p != null) {
            pivot = new Location(p.world(), p.x() + dx, p.y() + dy, p.z() + dz);
        }
    }

    /** Move the whole arrangement so its pivot lands on this point, keeping the members' relative places. */
    public void moveTo(double x, double y, double z) {
        Location from = getPivot();
        if (from != null) {
            move(x - from.x(), y - from.y(), z - from.z());
        }
    }

    /**
     * Turn the arrangement {@code degrees} around its pivot (the vertical axis), carrying each member's
     * own facing along with it — so a ring of props rotates as a ring rather than scattering.
     */
    public void rotate(double degrees) {
        Location p = getPivot();
        if (p == null) {
            return;
        }
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians), sin = Math.sin(radians);
        for (ScriptEntity entity : all()) {
            Location at = entity.getLocation();
            double dx = at.x() - p.x(), dz = at.z() - p.z();
            entity.moveTo(p.x() + dx * cos - dz * sin, at.y(), p.z() + dx * sin + dz * cos);
            entity.setRotation(at.yaw() + degrees, at.pitch());
        }
    }

    /** Give every member the same floating label ({@code null} clears it). */
    public void setNameTag(String nameTag) {
        for (ScriptEntity entity : all()) {
            entity.setNameTag(nameTag);
        }
    }

    /** Remove every member from the world. */
    public void remove() {
        for (ScriptEntity entity : all()) {
            entity.remove();
        }
        synchronized (members) {
            members.clear();
        }
    }

    /** The centre of the members' positions, or {@code null} if the group is empty. */
    private Location centre() {
        ScriptEntity[] all = all();
        if (all.length == 0) {
            return null;
        }
        double x = 0, y = 0, z = 0;
        for (ScriptEntity entity : all) {
            Location at = entity.getLocation();
            x += at.x();
            y += at.y();
            z += at.z();
        }
        return new Location(all[0].getLocation().world(), x / all.length, y / all.length, z / all.length);
    }
}
