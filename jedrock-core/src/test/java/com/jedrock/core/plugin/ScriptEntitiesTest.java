package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script entity layer end-to-end without a network: a script spawns bodies, drives them from a tick
 * handler, and a reload despawns them — the leak the per-plugin ownership exists to prevent.
 */
class ScriptEntitiesTest {

    private final CoreWorld world = new CoreWorld("entities", Dimension.OVERWORLD, 1L);

    /** A puppet that records its life instead of relaying to a network. */
    private static final class FakePuppet implements PuppetEntity {
        private final long entityId;
        private final EntityType type;
        private volatile Location location;
        private volatile boolean alive = true;
        private volatile String nameTag;
        private volatile int flags;
        volatile Consumer<Player> interact;
        volatile int itemState;

        FakePuppet(long entityId, EntityType type, Location location) {
            this.entityId = entityId;
            this.type = type;
            this.location = location;
        }

        @Override public EntityType getEntityType() { return type; }
        @Override public String getName() { return type.name(); }
        @Override public String getNameTag() { return nameTag; }
        @Override public void setNameTag(String nameTag) { this.nameTag = nameTag; }
        @Override public void teleport(Location to) { this.location = to; }
        @Override public void setRotation(float yaw, float pitch) {
            Location at = location;
            this.location = new Location(at.world(), at.x(), at.y(), at.z(), yaw, pitch);
        }
        @Override public void lookAt(Location target) { }
        @Override public boolean hasFlag(PuppetFlag flag) { return flag.isSet(flags); }
        @Override public void setFlag(PuppetFlag flag, boolean on) {
            flags = on ? (flags | flag.bit()) : (flags & ~flag.bit());
        }
        volatile int heldItem;
        final int[] armor = new int[com.jedrock.api.player.ArmorSlot.values().length];
        @Override public void setHeldItem(int state) { this.heldItem = state; }
        @Override public int getHeldItem() { return heldItem; }
        @Override public void setArmor(com.jedrock.api.player.ArmorSlot slot, int state) {
            armor[slot.ordinal()] = state;
        }
        @Override public int getArmor(com.jedrock.api.player.ArmorSlot slot) { return armor[slot.ordinal()]; }
        @Override public void swing() { }
        @Override public void hurt() { }
        @Override public void onInteract(Consumer<Player> handler) { this.interact = handler; }
        @Override public UUID getUniqueId() { return null; }
        @Override public long getEntityId() { return entityId; }
        @Override public World getWorld() { return location.world(); }
        @Override public Location getLocation() { return location; }
        @Override public void setLocation(Location location) { this.location = location; }
        @Override public void remove() { alive = false; }
        @Override public boolean isAlive() { return alive; }
        @Override public String getType() { return type.name(); }
    }

    /** The slice of Server the entity API touches: a world, a roster, and puppet spawning. */
    private final class FakeServer implements Server {
        final List<FakePuppet> spawned = new CopyOnWriteArrayList<>();
        final List<Player> players = new ArrayList<>();
        private long nextId = 1;

        @Override
        public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
            FakePuppet puppet = new FakePuppet(nextId++, type, at);
            spawned.add(puppet);
            return puppet;
        }

        @Override
        public PuppetEntity spawnItem(Location at, int state) {
            return prop(EntityType.ITEM, at, state);
        }

        @Override
        public PuppetEntity spawnFallingBlock(Location at, int state) {
            return prop(EntityType.FALLING_BLOCK, at, state);
        }

        @Override
        public PuppetEntity spawnText(Location at, String text) {
            FakePuppet puppet = (FakePuppet) prop(EntityType.TEXT, at, 0);
            puppet.setNameTag(text);
            return puppet;
        }

        private PuppetEntity prop(EntityType type, Location at, int state) {
            FakePuppet puppet = new FakePuppet(nextId++, type, at);
            puppet.itemState = state;
            spawned.add(puppet);
            return puppet;
        }

        long alive() {
            return spawned.stream().filter(FakePuppet::isAlive).count();
        }

        @Override public World getDefaultWorld() { return world; }
        @Override public Collection<World> getWorlds() { return List.of(world); }
        @Override public Optional<World> getWorld(String name) { return Optional.of(world); }
        @Override public Collection<Player> getPlayers() { return players; }
        @Override public Optional<Player> getPlayer(String name) { return Optional.empty(); }
        @Override public Optional<Player> getPlayer(UUID uuid) { return Optional.empty(); }
        @Override public String getName() { return "test"; }
        @Override public String getVersion() { return "test"; }
        @Override public void start() { }
        @Override public void shutdown() { }
        @Override public boolean isRunning() { return true; }
        @Override public EventBus getEventBus() { return new EventBus(); }
        @Override public void broadcast(String message) { }
        @Override public void dispatchCommand(Player player, String commandLine) { }
        @Override public Hologram spawnHologram(Location at, String... lines) { return null; }
        @Override public long getCurrentTick() { return 0; }
        @Override public ServerStatus getStatus() { return null; }
    }

    private final FakeServer server = new FakeServer();

    private PluginManager manager(Path dir, Scheduler scheduler) {
        return new PluginManager(new EventBus(), server, scheduler, new CommandManager(null),
                new PacketTapRegistry(), dir);
    }

    @Test
    void aScriptSpawnsDressesAndDrivesItsEntities(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        plugins.loadSource("mobs.js",
                "var pig = entities.spawn('pig', 10, 65, 10);\n"
              + "pig.setNameTag('{gold}Boss');\n"
              + "pig.setFlag('on_fire', true);\n"
              + "pig.set('goal', pig.getLocation().withPosition(13, 65, 10));\n"
              + "pig.onTick(function (e) { e.moveToward(e.get('goal'), 1.0); });\n", 1L);

        assertEquals(1, server.spawned.size(), "the script spawned one body");
        FakePuppet pig = server.spawned.get(0);
        assertEquals(EntityType.PIG, pig.getEntityType());
        assertEquals("{gold}Boss", pig.getNameTag(), "looks are applied through the wrapper");
        assertTrue(pig.hasFlag(PuppetFlag.ON_FIRE));
        assertEquals(1, plugins.entityCount("mobs.js"), "and it's owned by the plugin");

        // Each tick the brain runs and walks the body one block toward its goal, three blocks away.
        for (long tick = 1; tick <= 3; tick++) {
            scheduler.tick(tick);
        }
        assertEquals(13.0, pig.getLocation().x(), 1.0e-6, "walked to the goal");

        scheduler.tick(4); // a fourth tick must not overshoot it
        assertEquals(13.0, pig.getLocation().x(), 1.0e-6, "moveToward stops on the target");
        assertEquals(10.0, pig.getLocation().z(), 1.0e-6, "and never drifts off-axis");

        plugins.unloadAll();
    }

    @Test
    void anItemPropIsAnEntityLikeAnyOther(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        // A block state as a body: the decoration primitive. It poses at a fractional height a real
        // block could never occupy, and it moves and labels like any other entity.
        plugins.loadSource("decor.js",
                "var lamp = entities.spawnItem(89 << 4, 8.5, 66.25, 8.5);\n"
              + "lamp.setNameTag('{yellow}Lantern');\n"
              + "lamp.onTick(function (e) { e.moveTo(8.5, 66.25, 8.5); });\n", 1L);

        assertEquals(1, server.spawned.size());
        FakePuppet lamp = server.spawned.get(0);
        assertEquals(EntityType.ITEM, lamp.getEntityType(), "spawned through the item path");
        assertEquals(89 << 4, lamp.itemState, "its body is the glowstone state it was given");
        assertEquals("{yellow}Lantern", lamp.getNameTag());
        assertEquals(66.25, lamp.getLocation().y(), 1.0e-6, "a fractional height, mid-block");

        scheduler.tick(1); // props are tickable too — nothing about them is special
        assertTrue(lamp.isAlive());

        plugins.unloadAll();
        assertEquals(0, server.alive(), "and it despawns with its plugin");
    }

    @Test
    void aBlockCanBeWornOrRenderedFullSize(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        plugins.loadSource("decor.js",
                // A block worn on an invisible head — a block posed where no real block could be.
                "var statue = entities.spawn('zombie', 4, 64, 4);\n"
              + "statue.setFlag('invisible', true);\n"
              + "statue.setArmor('helmet', 89 << 4);\n"
              + "statue.setHeldItem(276 << 4);\n"
                // …and the same block again, full size, as its own prop.
              + "entities.spawnBlock(35 << 4 | 14, 6.5, 67.5, 6.5);\n", 1L);

        FakePuppet statue = server.spawned.get(0);
        assertEquals(89 << 4, statue.getArmor(com.jedrock.api.player.ArmorSlot.HELMET),
                "the glowstone is worn on its head");
        assertEquals(276 << 4, statue.getHeldItem(), "and a sword is in its hand");
        assertTrue(statue.hasFlag(PuppetFlag.INVISIBLE), "with the body hidden");

        FakePuppet block = server.spawned.get(1);
        assertEquals(EntityType.FALLING_BLOCK, block.getEntityType(), "full-size block prop");
        assertEquals(35 << 4 | 14, block.itemState, "red wool, meta preserved");
        assertEquals(67.5, block.getLocation().y(), 1.0e-6, "posed mid-block");

        plugins.unloadAll();
    }

    @Test
    void aGroupMovesRotatesAndClearsAsOne(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        plugins.loadSource("scene.js",
                // Four props on a circle of radius 2 around the origin, plus a label, as one scene.
                "var scene = entities.circle(4, 0, 64, 0, 2, function (x, y, z) {\n"
              + "    return entities.spawnItem(89 << 4, x, y, z);\n"
              + "});\n"
              + "scene.add(entities.spawnText('{yellow}Ring', 0, 66, 0));\n"
              + "scene.setPivot(0, 64, 0);\n"
              + "var placed = scene.size();\n"
                // A quarter turn carries the first prop from +x to +z, then the scene shifts bodily.
              + "scene.rotate(90);\n"
              + "scene.move(10, 0, 0);\n", 1L);

        assertEquals(5, server.spawned.size(), "four props placed on the shape, plus the label");
        assertEquals(EntityType.TEXT, server.spawned.get(4).getEntityType(), "the label is a text prop");
        assertEquals("{yellow}Ring", server.spawned.get(4).getNameTag());

        FakePuppet first = server.spawned.get(0);
        assertEquals(10.0, first.getLocation().x(), 1.0e-6, "rotated a quarter turn, then moved as one");
        assertEquals(2.0, first.getLocation().z(), 1.0e-6, "the turn carried it from +x round to +z");
        assertEquals(10.0, server.spawned.get(4).getLocation().x(), 1.0e-6, "the label travelled too");

        plugins.unloadAll();
        assertEquals(0, server.alive(), "unload clears the scene with its plugin");
    }

    @Test
    void reloadDespawnsTheOldScriptsEntities(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        plugins.loadSource("mobs.js", "entities.spawn('zombie', 0, 64, 0);", 1L);
        assertEquals(1, server.alive(), "one body from the first version");

        // A reload must not leave the old body standing with a brain from a torn-down scope.
        plugins.loadSource("mobs.js", "entities.spawn('zombie', 5, 64, 5);", 2L);

        assertEquals(2, server.spawned.size(), "the new version spawned its own");
        assertEquals(1, server.alive(), "…and exactly one body is alive — the old one was despawned");
        assertTrue(server.spawned.get(1).isAlive(), "the survivor is the new one");

        plugins.unloadAll();
        assertEquals(0, server.alive(), "unload clears the rest");
    }

    @Test
    void aTickHandlerStopsWithTheEntityItDrives(@TempDir Path dir) {
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(dir, scheduler);
        // The body drifts a block per tick, then removes itself on the second one; the position it
        // stops at is the record of how many times its brain ran.
        plugins.loadSource("mobs.js",
                "var ticks = 0;\n"
              + "var cow = entities.spawn('cow', 0, 64, 0);\n"
              + "cow.onTick(function (e) {\n"
              + "    ticks++;\n"
              + "    if (e.nearestPlayer(50) === null) e.moveTo(ticks, 64, 0);\n"
              + "    if (ticks === 2) e.remove();\n"
              + "});\n", 1L);

        for (long tick = 1; tick <= 5; tick++) {
            scheduler.tick(tick);
        }

        FakePuppet cow = server.spawned.get(0);
        assertFalse(cow.isAlive(), "it removed itself on its second tick");
        assertEquals(2.0, cow.getLocation().x(), 1.0e-6,
                "exactly two ticks ran — a removed entity's handler stops being called");
        assertEquals(0, plugins.entityCount("mobs.js"), "and it's off the plugin's roster");

        plugins.unloadAll();
    }
}
