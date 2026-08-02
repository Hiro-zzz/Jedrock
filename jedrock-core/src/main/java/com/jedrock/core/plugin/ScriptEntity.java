package com.jedrock.core.plugin;

import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import org.mozilla.javascript.Function;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One script-driven entity — a {@link PuppetEntity} plus everything a script needs to be its brain. The
 * server still simulates nothing: there is no AI, no pathfinding and no physics here. What a script gets
 * is a body it can move, dress and animate, a per-tick callback, a place to keep state, and the spatial
 * queries that make writing behaviour possible.
 *
 * <pre>{@code
 *   const guard = entities.spawn('zombie', x, y, z);
 *   guard.setNameTag('{red}Guard');
 *   guard.set('home', guard.getLocation());
 *
 *   guard.onTick(e => {
 *       const target = e.nearestPlayer(12);
 *       if (target) { e.lookAt(target); e.moveToward(target.getLocation(), 0.15); }
 *       else e.moveToward(e.get('home'), 0.1);
 *   });
 *
 *   guard.onInteract(who => who.sendMessage('{red}Back off, ' + who.getName()));
 * }</pre>
 *
 * Every callback runs on the game-loop thread under the script lock, like events and scheduled tasks, so
 * keep them quick. An entity is owned by the plugin that spawned it and is removed when that plugin is
 * unloaded or hot-reloaded — a reloaded script starts from a clean world rather than orphaned bodies.
 */
public final class ScriptEntity {

    private final PuppetEntity puppet;
    private final ScriptEntities owner;
    /** Free-form per-entity state a script keeps between ticks (its "memory"). */
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private volatile Function tickHandler;

    ScriptEntity(PuppetEntity puppet, ScriptEntities owner) {
        this.puppet = puppet;
        this.owner = owner;
    }

    /** The underlying puppet — the escape hatch for anything this wrapper doesn't cover. */
    public PuppetEntity puppet() {
        return puppet;
    }

    Function tickHandler() {
        return tickHandler;
    }

    // ===== Identity and place =====

    /** The entity id every edition addresses this body by. */
    public long getEntityId() {
        return puppet.getEntityId();
    }

    /** The canonical type name it renders as, lower-case ({@code 'zombie'}, {@code 'pig'}…). */
    public String getType() {
        return puppet.getEntityType().name().toLowerCase(Locale.ROOT);
    }

    public Location getLocation() {
        return puppet.getLocation();
    }

    public double getX() {
        return puppet.getLocation().x();
    }

    public double getY() {
        return puppet.getLocation().y();
    }

    public double getZ() {
        return puppet.getLocation().z();
    }

    /** Whether this body is still in the world (a removed entity is inert — every call is a no-op). */
    public boolean isAlive() {
        return puppet.isAlive();
    }

    // ===== Movement (the script is the puppeteer — every call is an instant, relayed move) =====

    /** Move to an absolute position, keeping the current facing. */
    public void moveTo(double x, double y, double z) {
        Location at = puppet.getLocation();
        puppet.teleport(new Location(at.world(), x, y, z, at.yaw(), at.pitch()));
    }

    /** Move to a {@link Location}, taking its facing too. */
    public void teleport(Location to) {
        puppet.teleport(to);
    }

    /**
     * Step at most {@code speed} blocks toward {@code target} — the one movement helper worth having,
     * because "walk toward the player" is most of what a script wants and it is pure arithmetic, not
     * pathfinding: it walks through walls as happily as across a field. Call it once per tick (0.1-0.2
     * blocks looks like a walk). Stops exactly on the target rather than overshooting.
     */
    public void moveToward(Location target, double speed) {
        if (target == null || speed <= 0) {
            return;
        }
        Location at = puppet.getLocation();
        double dx = target.x() - at.x(), dy = target.y() - at.y(), dz = target.z() - at.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0e-4) {
            return;
        }
        double step = Math.min(speed, distance) / distance;
        moveTo(at.x() + dx * step, at.y() + dy * step, at.z() + dz * step);
    }

    /** Turn in place (degrees). */
    public void setRotation(double yaw, double pitch) {
        puppet.setRotation((float) yaw, (float) pitch);
    }

    /** Turn to face a point, a player or another entity — the cheapest illusion of attention there is. */
    public void lookAt(Object target) {
        Location at = locationOf(target);
        if (at != null) {
            puppet.lookAt(at);
        }
    }

    /**
     * Aim the <b>head</b> at something, leaving the body facing where it stands — a guard who watches you
     * cross the room without turning to follow.
     *
     * <pre>{@code
     *   e.onTick(function () {
     *       var p = e.nearestPlayer(12);
     *       if (p) { e.glanceAt(p); }        // …and the guard keeps facing its post
     *   });
     * }</pre>
     */
    public void glanceAt(Object target) {
        Location at = locationOf(target);
        if (at != null) {
            puppet.glanceAt(at);
        }
    }

    /** Where the head is aimed, in degrees. */
    public double getHeadYaw() {
        return puppet.getHeadYaw();
    }

    /** Turn the head alone. How far a neck bends is the client's opinion, not the server's. */
    public void setHeadYaw(double headYaw) {
        puppet.setHeadYaw((float) headYaw);
    }

    // ===== Looks =====

    /** Floating text above the entity, in the unified {@code {color}} markup; null or empty removes it. */
    public void setNameTag(String nameTag) {
        puppet.setNameTag(nameTag);
    }

    public String getNameTag() {
        return puppet.getNameTag();
    }

    /** Set a visual flag by name, case-insensitive: {@code 'on_fire'}, {@code 'invisible'}, {@code 'sneaking'}. */
    public void setFlag(String flag, boolean on) {
        puppet.setFlag(parseFlag(flag), on);
    }

    public boolean hasFlag(String flag) {
        return puppet.hasFlag(parseFlag(flag));
    }

    /** Put an item or block in the entity's hand ({@code 0} empties it) — a guard with a sword. */
    public void setHeldItem(int state) {
        puppet.setHeldItem(state);
    }

    public int getHeldItem() {
        return puppet.getHeldItem();
    }

    /**
     * Dress the entity: {@code setArmor('helmet', Blocks.state(89, 0))} puts a glowstone block on its
     * head. Combined with {@code setFlag('invisible', true)} that is a block posed at any height and
     * angle with nothing holding it up — the trick real blocks can't do.
     */
    public void setArmor(String slot, int state) {
        puppet.setArmor(parseArmorSlot(slot), state);
    }

    public int getArmor(String slot) {
        return puppet.getArmor(parseArmorSlot(slot));
    }

    /** Play the arm-swing animation on every viewer's client. */
    public void swing() {
        puppet.swing();
    }

    /** Play the hurt flash. There is no health here — this is the look of damage, not damage. */
    public void hurt() {
        puppet.hurt();
    }

    // ===== State: the entity's memory between ticks =====

    /** Store a value under {@code key} (null removes it). Kept for as long as the entity lives. */
    public void set(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    /** Read a stored value, or {@code null} if nothing is under {@code key}. */
    public Object get(String key) {
        return data.get(key);
    }

    /** Whether anything is stored under {@code key}. */
    public boolean has(String key) {
        return data.containsKey(key);
    }

    // ===== Behaviour =====

    /**
     * Run {@code fn} every server tick (20/sec) with this entity as the argument — where a script writes
     * its behaviour. Passing {@code null} stops it. The handler runs on the game-loop thread, so keep it
     * cheap: it holds up the tick while it runs.
     */
    public void onTick(Function fn) {
        this.tickHandler = fn;
        if (fn != null) {
            owner.ensureTicking();
        }
    }

    /** Run {@code fn} when a player interacts with (hits) this entity. Passing {@code null} clears it. */
    public void onInteract(Function fn) {
        owner.bindInteract(this, fn);
    }

    // ===== Queries — what a brain needs to see =====

    /** The nearest player within {@code radius} blocks, or {@code null} if nobody is that close. */
    public Player nearestPlayer(double radius) {
        return owner.nearestPlayer(puppet.getLocation(), radius);
    }

    /** Distance in blocks to a point, a player or another entity; {@code -1} if the target is unusable. */
    public double distanceTo(Object target) {
        Location to = locationOf(target);
        if (to == null) {
            return -1;
        }
        Location at = puppet.getLocation();
        double dx = to.x() - at.x(), dy = to.y() - at.y(), dz = to.z() - at.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Remove this entity from the world. Its tick and interaction callbacks stop with it. */
    public void remove() {
        owner.forget(this);
        puppet.remove();
    }

    /** Accept a Location, a Player or another ScriptEntity wherever a point is wanted. */
    static Location locationOf(Object target) {
        if (target instanceof Location location) {
            return location;
        }
        // A script's player is the script contract, not the core object — see ScriptWrapFactory.
        Player player = ScriptWrapFactory.unwrapPlayer(target);
        if (player != null) {
            return player.getLocation();
        }
        if (target instanceof ScriptEntity entity) {
            return entity.getLocation();
        }
        if (target instanceof PuppetEntity puppet) {
            return puppet.getLocation();
        }
        return null;
    }

    private static com.jedrock.api.player.ArmorSlot parseArmorSlot(String name) {
        try {
            return com.jedrock.api.player.ArmorSlot.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown armor slot '" + name + "' — one of: "
                    + java.util.Arrays.toString(com.jedrock.api.player.ArmorSlot.values()));
        }
    }

    private static PuppetFlag parseFlag(String name) {
        try {
            return PuppetFlag.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown entity flag '" + name + "' — one of: "
                    + java.util.Arrays.toString(PuppetFlag.values()));
        }
    }
}
