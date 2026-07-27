package com.jedrock.core.entity;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoration used to last exactly as long as the script that built it: a restart, or even a hot reload,
 * took the lanterns away and the scene only existed while its code kept running. A saved scene is the
 * other thing — props frozen as they stood, restored by the server with no script involved.
 *
 * <p>So these pin what "saved" has to mean: the look survives a round trip through the file, standing a
 * scene up twice doesn't breed copies, and removing one takes it out of the world as well as the store.
 */
class SceneManagerTest {

    private final CoreWorld world = new CoreWorld("scenes", Dimension.OVERWORLD, 1L);
    private final PlayerRegistry players = new PlayerRegistry();
    private final EntityDirector entities = new EntityDirector(players, world);

    private SceneManager manager() {
        return new SceneManager(entities, world);
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z, 90f, 15f);
    }

    @Test
    void aSceneComesBackLookingTheSame(@TempDir Path dir) throws Exception {
        SceneManager scenes = manager();
        PuppetEntity guard = entities.spawnPuppet(EntityType.ZOMBIE, at(10, 64, 20), "Guard");
        guard.setNameTag("{red}Keep out");
        guard.setHeldItem(Blocks.state(276, 0));                 // diamond sword
        guard.setArmor(ArmorSlot.HELMET, Blocks.state(310, 0));
        guard.setFlag(PuppetFlag.ON_FIRE, true);
        PuppetEntity lantern = entities.spawnItem(at(10.5, 66, 20.5), Blocks.state(89, 0));

        scenes.save("gate", List.of(guard, lantern));
        Path file = dir.resolve("scenes.jdb");
        scenes.save(file);

        // A fresh manager, as after a restart: nothing in memory, everything from the file.
        SceneManager reloaded = manager();
        reloaded.load(file);
        assertEquals(List.of("gate"), reloaded.names());
        assertEquals(2, reloaded.size("gate"));

        List<PuppetEntity> standing = reloaded.spawn("gate");
        assertEquals(2, standing.size());

        PuppetEntity restoredGuard = standing.get(0);
        assertEquals(EntityType.ZOMBIE, restoredGuard.getEntityType());
        assertEquals("{red}Keep out", restoredGuard.getNameTag(), "the name tag is part of the look");
        assertEquals(Blocks.state(276, 0), restoredGuard.getHeldItem());
        assertEquals(Blocks.state(310, 0), restoredGuard.getArmor(ArmorSlot.HELMET));
        assertTrue(restoredGuard.hasFlag(PuppetFlag.ON_FIRE));
        assertEquals(10, restoredGuard.getLocation().x());
        assertEquals(90f, restoredGuard.getLocation().yaw(), "facing survives too — a prop that turned matters");

        PuppetEntity restoredLantern = standing.get(1);
        assertEquals(EntityType.ITEM, restoredLantern.getEntityType());
        assertEquals(10.5, restoredLantern.getLocation().x());
        assertNotEquals(restoredGuard.getEntityId(), restoredLantern.getEntityId());
    }

    @Test
    void loadingTwiceDoesNotBreedCopies(@TempDir Path dir) {
        SceneManager scenes = manager();
        scenes.save("row", List.of(entities.spawnItem(at(1, 64, 1), Blocks.state(89, 0))));

        List<PuppetEntity> first = scenes.spawn("row");
        List<PuppetEntity> second = scenes.spawn("row");

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(first.get(0).getEntityId(), second.get(0).getEntityId(),
                "a script asking for its scene on every reload must not fill the world with duplicates");
    }

    @Test
    void removingTakesItOutOfTheWorldAndTheStore(@TempDir Path dir) throws Exception {
        SceneManager scenes = manager();
        scenes.save("temp", List.of(entities.spawnItem(at(2, 64, 2), Blocks.state(89, 0))));
        PuppetEntity standing = scenes.spawn("temp").get(0);

        assertTrue(scenes.remove("temp"));
        assertFalse(standing.isAlive(), "the prop is gone from the world, not just from the file");
        assertEquals(List.of(), scenes.names());
        assertFalse(scenes.remove("temp"), "removing what isn't there says so");

        // And the removal is what gets written out, not the scene.
        Path file = dir.resolve("scenes.jdb");
        scenes.save(file);
        SceneManager reloaded = manager();
        reloaded.load(file);
        assertEquals(List.of(), reloaded.names());
    }

    @Test
    void anUnknownSceneIsEmptyRatherThanAnError(@TempDir Path dir) throws Exception {
        SceneManager scenes = manager();
        assertEquals(List.of(), scenes.spawn("never-saved"),
                "a script asking for a scene it never saved gets nothing, not a broken startup");
        assertEquals(-1, scenes.size("never-saved"));
        assertFalse(scenes.has("never-saved"));

        // A missing file is not an error either — it means nothing has been saved yet.
        scenes.load(dir.resolve("absent.jdb"));
        assertEquals(List.of(), scenes.names());
        assertFalse(Files.exists(dir.resolve("absent.jdb")));
    }

    @Test
    void anUntouchedStoreIsNeverRewritten(@TempDir Path dir) throws Exception {
        SceneManager scenes = manager();
        scenes.save("one", List.of(entities.spawnItem(at(3, 64, 3), Blocks.state(89, 0))));
        Path file = dir.resolve("scenes.jdb");
        scenes.saveIfDirty(file);
        long first = Files.getLastModifiedTime(file).toMillis();

        Files.delete(file);
        scenes.saveIfDirty(file);   // nothing changed since the last write

        assertFalse(Files.exists(file), "a dirty flag is what keeps an idle server from rewriting the file");
        assertTrue(first > 0);
    }
}
