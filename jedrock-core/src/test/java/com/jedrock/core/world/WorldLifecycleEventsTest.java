package com.jedrock.core.world;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.world.WorldCreateEvent;
import com.jedrock.api.event.world.WorldLoadEvent;
import com.jedrock.api.event.world.WorldUnloadEvent;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.WorldTemplate;
import com.jedrock.core.player.PlayerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world arriving and leaving.
 *
 * <p>The distinction being pinned is the one a script has to be able to rely on: <b>created</b> happens
 * once in a world's life and is the only safe moment to carve something into it, while <b>loaded</b>
 * happens every boot. Getting that backwards means re-cutting an arena over whatever players built there
 * since, which is the kind of mistake that only shows up on somebody else's server a week later.
 */
class WorldLifecycleEventsTest {

    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();

    private static WorldTemplate tiny(String name) {
        return new WorldTemplate(name, Dimension.OVERWORLD, 2, false, 42L);
    }

    private WorldManager manager(Path root) {
        return new WorldManager(events, players, root);
    }

    @Test
    void aFreshWorldIsCreatedThenLoaded(@TempDir Path dir) {
        List<String> log = new ArrayList<>();
        events.register(WorldCreateEvent.class, e -> log.add("created " + e.getWorld().getName()
                + " from " + e.getTemplate()));
        events.register(WorldLoadEvent.class, e -> log.add("loaded " + e.getWorld().getName()
                + " new=" + e.isCreated()));

        manager(dir).create("arena", tiny("tiny"), 1L);

        assertEquals(List.of("created arena from tiny", "loaded arena new=true"), log,
                "made, then made available — and in that order, so a listener can edit real terrain");
    }

    @Test
    void aWorldReadOffDiskIsOnlyLoaded(@TempDir Path dir) throws IOException {
        WorldManager first = manager(dir);
        first.create("arena", tiny("tiny"), 1L);
        first.unload("arena");

        List<String> log = new ArrayList<>();
        events.register(WorldCreateEvent.class, e -> log.add("created"));
        events.register(WorldLoadEvent.class, e -> log.add("loaded new=" + e.isCreated()));

        manager(dir).openExisting("arena");

        assertEquals(List.of("loaded new=false"), log,
                "the second boot must not look like the first, or a script re-cuts what players built");
    }

    @Test
    void aListenerIsHandedTerrainItCanActuallyEdit(@TempDir Path dir) {
        // Both events fire after the bake and after the change relay is wired, which is the difference
        // between "you may decorate this world" and "you may decorate this empty object".
        events.register(WorldCreateEvent.class, e -> e.getWorld().setBlock(0, 70, 0, 1, 0));

        CoreWorld world = manager(dir).create("arena", tiny("tiny"), 1L);

        assertEquals(1 << 4, world.getBlockId(0, 70, 0), "the block a create listener placed is there");
    }

    @Test
    void unloadingAnnouncesItAndCanBeRefused(@TempDir Path dir) {
        WorldManager worlds = manager(dir);
        worlds.create("arena", tiny("tiny"), 1L);
        List<WorldUnloadEvent> seen = new ArrayList<>();
        events.register(WorldUnloadEvent.class, seen::add);
        events.register(WorldUnloadEvent.class, e -> e.setCancelled(true));

        assertFalse(worlds.unload("arena"), "a listener refused it");

        assertEquals(1, seen.size());
        assertEquals("arena", seen.get(0).getWorld().getName());
        assertTrue(worlds.get("arena").isPresent(), "and it is still loaded, untouched");
    }

    @Test
    void anUnrefusedUnloadGoesThrough(@TempDir Path dir) {
        WorldManager worlds = manager(dir);
        worlds.create("arena", tiny("tiny"), 1L);
        List<WorldUnloadEvent> seen = new ArrayList<>();
        events.register(WorldUnloadEvent.class, seen::add);

        assertTrue(worlds.unload("arena"));

        assertEquals(1, seen.size());
        assertFalse(worlds.get("arena").isPresent());
    }

    @Test
    void theDefaultWorldIsNeverEvenOffered(@TempDir Path dir) {
        WorldManager worlds = manager(dir);
        worlds.openDefault("world", tiny("tiny"));
        List<WorldUnloadEvent> seen = new ArrayList<>();
        events.register(WorldUnloadEvent.class, seen::add);

        assertFalse(worlds.unload("world"));

        assertTrue(seen.isEmpty(), "the server's own refusal comes first — there is nothing to vote on");
    }

    @Test
    void loadingTwiceDoesNotAnnounceTwice(@TempDir Path dir) throws IOException {
        WorldManager worlds = manager(dir);
        worlds.create("arena", tiny("tiny"), 1L);
        List<WorldLoadEvent> seen = new ArrayList<>();
        events.register(WorldLoadEvent.class, seen::add);

        assertSame(worlds.openExisting("arena"), worlds.openExisting("arena"));

        assertTrue(seen.isEmpty(), "it was already loaded — asking for it again is not an arrival");
    }
}
