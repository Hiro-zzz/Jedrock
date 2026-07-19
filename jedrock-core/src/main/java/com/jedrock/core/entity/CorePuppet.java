package com.jedrock.core.entity;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.JedrockServer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A server-puppeteered visual entity — the illusionist's mob / NPC / hologram. It holds only presentational
 * state (type, position) and an optional interaction callback; it is never simulated. Movement and removal
 * relay to viewers through the {@link JedrockServer} that owns it. This is the primitive the platform API
 * will drive.
 */
public final class CorePuppet implements PuppetEntity {

    private final UUID uuid = UUID.randomUUID();
    private final long entityId = EntityIds.next();
    private final EntityType type;
    private final String name;
    private final JedrockServer server;

    /**
     * The height a puppet "looks from" when aiming at something. Nominal and deliberately not per-type: the
     * illusion only needs the head pointed roughly right, and a table of per-mob eye heights would be the
     * start of exactly the simulation this server refuses to do.
     */
    private static final double NOMINAL_EYE_HEIGHT = 1.62;

    private volatile World world;
    private volatile Location location;
    private volatile Consumer<Player> interactHandler;
    private volatile boolean alive = true;
    private volatile String nameTag;
    private volatile int flags;

    public CorePuppet(EntityType type, String name, World world, Location location, JedrockServer server) {
        this.type = type;
        this.name = name;
        this.world = world;
        this.location = location;
        this.server = server;
    }

    // ===== Entity =====

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

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
            server.removePuppet(this); // relays the despawn to every viewer
        }
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public String getType() {
        return "minecraft:" + type.canonicalName();
    }

    // ===== PuppetEntity =====

    @Override
    public EntityType getEntityType() {
        return type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getNameTag() {
        return nameTag;
    }

    @Override
    public void setNameTag(String nameTag) {
        this.nameTag = nameTag;
        server.relayPuppetNameTag(this); // relays the new text to every viewer
    }

    @Override
    public void teleport(Location to) {
        setLocation(to);
        server.movePuppet(this, to); // relays the move to every viewer
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        Location at = location;
        teleport(new Location(at.world(), at.x(), at.y(), at.z(), yaw, pitch));
    }

    @Override
    public void lookAt(Location target) {
        Location at = location;
        double dx = target.x() - at.x();
        double dy = target.y() - (at.y() + NOMINAL_EYE_HEIGHT);
        double dz = target.z() - at.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, flat));
        setRotation(yaw, pitch);
    }

    @Override
    public boolean hasFlag(PuppetFlag flag) {
        return flag.isSet(flags);
    }

    @Override
    public void setFlag(PuppetFlag flag, boolean on) {
        // Every edition packs these into one field, so the whole set is what gets relayed.
        this.flags = on ? (flags | flag.bit()) : (flags & ~flag.bit());
        server.relayPuppetFlags(this);
    }

    /** The puppet's whole canonical flag mask — what the relay hands the network layer. */
    public int getFlags() {
        return flags;
    }

    @Override
    public void swing() {
        server.relayPuppetSwing(this);
    }

    @Override
    public void hurt() {
        server.relayPuppetHurt(this);
    }

    @Override
    public void onInteract(Consumer<Player> handler) {
        this.interactHandler = handler;
    }

    /** Fire the interaction callback (a player attacked this puppet). Core-internal — not part of the api. */
    public void fireInteract(Player attacker) {
        Consumer<Player> handler = this.interactHandler;
        if (handler != null) {
            handler.accept(attacker);
        }
    }
}
