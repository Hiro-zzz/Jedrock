package com.jedrock.core.world;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.WorldTemplate;
import com.jedrock.core.player.PlayerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world registry: making worlds from templates, finding the ones already on disk, and refusing the
 * things that would quietly corrupt either.
 *
 * <p>Every world here is built from a deliberately tiny template — a 48×48 bake is a few seconds, and
 * these tests make several worlds each.
 */
class WorldManagerTest {

    private static final WorldTemplate TINY =
            new WorldTemplate("tiny", Dimension.OVERWORLD, 2, false, 42L);
    private static final WorldTemplate TINY_NETHER =
            new WorldTemplate("tiny_nether", Dimension.NETHER, 2, false, 42L);

    private WorldManager manager(Path root) {
        return new WorldManager(new EventBus(), new PlayerRegistry(), root);
    }

    @Test
    void aWorldIsBakedAndItsFolderWritten(@TempDir Path root) {
        WorldManager worlds = manager(root);
        CoreWorld world = worlds.create("arena", TINY, null);

        assertTrue(world.isGenerated(), "a created world is baked before it is handed back");
        assertEquals(2, world.boundsChunks());
        assertTrue(Files.isRegularFile(root.resolve("arena").resolve("level.jdw")),
                "and written, so the next boot just loads it");
        assertEquals(world, worlds.get("ARENA").orElse(null), "found case-insensitively");
    }

    @Test
    void aNetherTemplateMakesANether(@TempDir Path root) {
        CoreWorld world = manager(root).create("hell", TINY_NETHER, null);

        assertEquals(Dimension.NETHER, world.getDimension());
        assertEquals(NetherGenerator.MAX_Y, world.maxY(), "128 tall, on every edition");
        assertEquals(Blocks.state(Blocks.BEDROCK, 0), world.getBlockId(0, 0, 0));
        assertEquals(NetherGenerator.BIOME_HELL, world.getBiome(0, 0));
    }

    @Test
    void aWorldFolderIsDiscoveredAtTheNextBootWithItsOwnKind(@TempDir Path root) {
        manager(root).create("hell", TINY_NETHER, 7L);

        // A fresh manager, as a restart would build: nothing told it what 'hell' is but the file.
        WorldManager reborn = manager(root);
        reborn.openDefault("world", TINY.withSeed(1L));
        int found = reborn.discover();

        assertEquals(1, found);
        CoreWorld hell = reborn.get("hell").orElseThrow();
        assertEquals(Dimension.NETHER, hell.getDimension(), "the level file is self-describing");
        assertEquals(7L, hell.getSeed());
        assertEquals(2, hell.boundsChunks(), "and remembers how big it was baked");
    }

    @Test
    void aSeedMakesTheSameWorldTwice(@TempDir Path a, @TempDir Path b) {
        CoreWorld first = manager(a).create("w", TINY, 12345L);
        CoreWorld second = manager(b).create("w", TINY, 12345L);

        int surface = first.surfaceHeight(3, 5);
        assertEquals(surface, second.surfaceHeight(3, 5));
        assertEquals(first.getBlockId(3, surface, 5), second.getBlockId(3, surface, 5));
    }

    @Test
    void aTemplateWithoutASeedGivesEachWorldItsOwn(@TempDir Path root) {
        WorldTemplate random = new WorldTemplate("r", Dimension.OVERWORLD, 2, false, null);
        WorldManager worlds = manager(root);

        CoreWorld one = worlds.create("one", random, null);
        CoreWorld two = worlds.create("two", random, null);

        assertTrue(one.getSeed() != two.getSeed(), "two worlds from one template are not clones");
    }

    @Test
    void aNameThatIsNotAFolderNameIsRefused(@TempDir Path root) {
        WorldManager worlds = manager(root);
        for (String bad : new String[]{"../etc", "a/b", "", "  ", "world:1", "plugins"}) {
            assertThrows(IllegalArgumentException.class, () -> worlds.create(bad, TINY, null),
                    "'" + bad + "' must not become a path");
        }
    }

    @Test
    void aNameAlreadyTakenIsRefusedRatherThanOverwritten(@TempDir Path root) {
        WorldManager worlds = manager(root);
        worlds.create("arena", TINY, null);

        assertThrows(IllegalStateException.class, () -> worlds.create("arena", TINY, null));
        // …and still refused after a restart, when it is only a folder on disk.
        WorldManager reborn = manager(root);
        assertThrows(IllegalStateException.class, () -> reborn.create("arena", TINY, null));
    }

    @Test
    void theDefaultWorldIsLoadedIfItIsThereAndCreatedIfItIsNot(@TempDir Path root) {
        WorldManager first = manager(root);
        CoreWorld created = first.openDefault("world", TINY.withSeed(99L));
        created.setBlockId(0, 200, 0, Blocks.state(Blocks.GLASS, 0));
        first.saveAllIfDirty();

        WorldManager second = manager(root);
        CoreWorld loaded = second.openDefault("world", TINY.withSeed(12345L)); // a different configured seed
        assertEquals(99L, loaded.getSeed(), "the file's terrain wins over the config's seed");
        assertEquals(Blocks.state(Blocks.GLASS, 0), loaded.getBlockId(0, 200, 0), "and its edits came back");
    }

    @Test
    void theDefaultWorldNeverUnloads(@TempDir Path root) {
        WorldManager worlds = manager(root);
        worlds.openDefault("world", TINY.withSeed(1L));
        assertFalse(worlds.unload("world"));
        assertNotNull(worlds.get("world").orElse(null));
    }

    @Test
    void unloadingSavesAndForgets(@TempDir Path root) {
        WorldManager worlds = manager(root);
        worlds.openDefault("world", TINY.withSeed(1L));
        CoreWorld arena = worlds.create("arena", TINY, null);
        arena.setBlockId(0, 100, 0, Blocks.state(Blocks.GLASS, 0));

        assertTrue(worlds.unload("arena"));
        assertTrue(worlds.get("arena").isEmpty());
        assertTrue(Files.isRegularFile(root.resolve("arena").resolve("level.jdw")),
                "the folder stays — an unload is not a delete");

        // What it saved on the way out is what comes back.
        WorldManager reborn = manager(root);
        reborn.openDefault("world", TINY.withSeed(1L));
        reborn.discover();
        assertEquals(Blocks.state(Blocks.GLASS, 0),
                reborn.get("arena").orElseThrow().getBlockId(0, 100, 0));
    }

    @Test
    void theBuiltInTemplatesAreRegisteredAndScriptsMayAddMore(@TempDir Path root) {
        WorldManager worlds = manager(root);
        for (String name : new String[]{"overworld", "nether", "overworld_small", "nether_small", "bare"}) {
            assertTrue(worlds.template(name).isPresent(), name + " is built in");
        }
        worlds.registerTemplate(TINY.named("mine"));
        assertEquals(Dimension.OVERWORLD, worlds.template("MINE").orElseThrow().dimension());
    }

    @Test
    void aTemplateRefusesASizeThatWouldEatTheHeap() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldTemplate("huge", Dimension.OVERWORLD, 4096, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldTemplate("end", Dimension.END, 16, true, null));
    }
}
