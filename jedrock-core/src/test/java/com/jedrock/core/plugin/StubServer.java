package com.jedrock.core.plugin;

import com.jedrock.api.ServerStatus;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.world.CoreWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The least server a script test can be given: a name, a world and a roster.
 *
 * <p>Its puppet and hologram each carry one public method no api interface declares, and that is the
 * point — narrowing the script surface is only observable against an implementation that has something
 * extra to hide, so these stand in for the real ones' internals.
 */
final class StubServer implements com.jedrock.api.Server {

    private final CoreWorld world;
    private final List<Player> players = new ArrayList<>();

    StubServer(CoreWorld world) {
        this.world = world;
    }

    void add(Player player) {
        players.add(player);
    }

    @Override public String getName() { return "stub"; }
    @Override public String getVersion() { return "test"; }
    @Override public void start() { }
    @Override public void shutdown() { }
    @Override public boolean isRunning() { return true; }
    @Override public EventBus getEventBus() { return new EventBus(); }
    @Override public Collection<Player> getPlayers() { return players; }
    @Override public Optional<Player> getPlayer(String name) {
        return players.stream().filter(p -> p.getName().equals(name)).findFirst();
    }
    @Override public Optional<Player> getPlayer(UUID uuid) {
        return players.stream().filter(p -> p.getUniqueId().equals(uuid)).findFirst();
    }
    @Override public void broadcast(String message) { }
    @Override public void dispatchCommand(Player player, String line) { }
    @Override public Collection<World> getWorlds() { return List.of(world); }
    @Override public Optional<World> getWorld(String name) { return Optional.of(world); }
    @Override public World getDefaultWorld() { return world; }
    @Override public World createWorld(String name, String template, Long seed) {
        throw new UnsupportedOperationException("the stub server has exactly one world");
    }
    @Override public boolean unloadWorld(String name) { return false; }
    @Override public Collection<com.jedrock.api.world.WorldTemplate> getWorldTemplates() { return List.of(); }
    @Override public void registerWorldTemplate(com.jedrock.api.world.WorldTemplate template) { }
    @Override public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
        return new StubPuppet(at);
    }
    @Override public PuppetEntity spawnItem(Location at, int state) { return new StubPuppet(at); }
    @Override public PuppetEntity spawnFallingBlock(Location at, int state) { return new StubPuppet(at); }
    @Override public PuppetEntity spawnText(Location at, String text) { return new StubPuppet(at); }
    @Override public Hologram spawnHologram(Location at, String... lines) {
        return new StubHologram(at, lines);
    }
    @Override public long getCurrentTick() { return 0; }
    @Override public ServerStatus getStatus() { return null; }

    /** A puppet with one method no interface declares — a script must not see it. */
    static final class StubPuppet implements PuppetEntity {
        private Location at;
        private String nameTag = "";

        StubPuppet(Location at) {
            this.at = at;
        }

        public String secretDoor() { return "leak"; }

        @Override public EntityType getEntityType() { return EntityType.ZOMBIE; }
        @Override public String getName() { return "stub"; }
        @Override public String getNameTag() { return nameTag; }
        @Override public void setNameTag(String tag) { this.nameTag = tag; }
        @Override public void teleport(Location to) { this.at = to; }
        @Override public void setRotation(float yaw, float pitch) { }
        @Override public void lookAt(Location target) { }
        @Override public boolean hasFlag(PuppetFlag flag) { return false; }
        @Override public void setFlag(PuppetFlag flag, boolean on) { }
        @Override public void setHeldItem(int state) { }
        @Override public int getHeldItem() { return 0; }
        @Override public void setArmor(ArmorSlot slot, int state) { }
        @Override public int getArmor(ArmorSlot slot) { return 0; }
        @Override public void swing() { }
        @Override public void hurt() { }
        @Override public void onInteract(Consumer<Player> handler) { }
        @Override public UUID getUniqueId() { return UUID.randomUUID(); }
        @Override public long getEntityId() { return 7L; }
        @Override public World getWorld() { return at.world(); }
        @Override public Location getLocation() { return at; }
        @Override public void setLocation(Location location) { this.at = location; }
        @Override public void remove() { }
        @Override public boolean isAlive() { return true; }
        @Override public String getType() { return "zombie"; }
    }

    /** Likewise for a hologram. */
    static final class StubHologram implements Hologram {
        private Location at;
        private List<String> lines;

        StubHologram(Location at, String... lines) {
            this.at = at;
            this.lines = List.of(lines);
        }

        public String secretDoor() { return "leak"; }

        @Override public List<String> getLines() { return lines; }
        @Override public void setLines(String... lines) { this.lines = List.of(lines); }
        @Override public void setLine(int index, String text) { }
        @Override public void teleport(Location to) { this.at = to; }
        @Override public UUID getUniqueId() { return UUID.randomUUID(); }
        @Override public long getEntityId() { return 8L; }
        @Override public World getWorld() { return at.world(); }
        @Override public Location getLocation() { return at; }
        @Override public void setLocation(Location location) { this.at = location; }
        @Override public void remove() { }
        @Override public boolean isAlive() { return true; }
        @Override public String getType() { return "hologram"; }
    }
}
