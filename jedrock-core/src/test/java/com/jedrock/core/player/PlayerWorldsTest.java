package com.jedrock.core.player;

import com.jedrock.core.data.FlatFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one fact this store holds — which world a player was last in — and the three ways it is asked for it:
 * across a restart, when nothing was ever recorded, and when the file has been edited by hand.
 */
class PlayerWorldsTest {

    @TempDir
    Path dir;

    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALEX = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    void survivesARestart() {
        PlayerWorlds first = new PlayerWorlds(new FlatFileStore(dir));
        first.remember(STEVE, "hell");
        first.remember(ALEX, "arena");

        PlayerWorlds reloaded = new PlayerWorlds(new FlatFileStore(dir)); // the restart this whole class exists for
        assertEquals("hell", reloaded.worldOf(STEVE));
        assertEquals("arena", reloaded.worldOf(ALEX));
    }

    @Test
    void aPlayerWhoNeverLeftHasNoEntry() {
        PlayerWorlds worlds = new PlayerWorlds(new FlatFileStore(dir));
        assertNull(worlds.worldOf(STEVE), "no entry means the default world, not an empty name");
        assertNull(worlds.worldOf(null));
        assertEquals(0, worlds.size());
    }

    @Test
    void rememberingTheSameWorldTwiceChangesNothing() {
        PlayerWorlds worlds = new PlayerWorlds(new FlatFileStore(dir));
        assertTrue(worlds.remember(STEVE, "hell"), "first crossing is a change");
        assertFalse(worlds.remember(STEVE, "hell"), "arriving where we already thought they were is not");
        assertTrue(worlds.remember(STEVE, "world"), "walking back is");
        assertEquals("world", worlds.worldOf(STEVE));
        assertEquals(1, worlds.size(), "one entry per player, not one per journey");
    }

    @Test
    void aBlankWorldIsNeverRecorded() {
        PlayerWorlds worlds = new PlayerWorlds(new FlatFileStore(dir));
        assertFalse(worlds.remember(STEVE, null));
        assertFalse(worlds.remember(STEVE, "  "));
        assertFalse(worlds.remember(null, "hell"));
        assertEquals(0, worlds.size());
    }

    @Test
    void forgettingReportsWhetherThereWasAnythingToForget() {
        PlayerWorlds worlds = new PlayerWorlds(new FlatFileStore(dir));
        worlds.remember(STEVE, "hell");

        assertTrue(worlds.forget(STEVE), "the world went away, so the entry does too");
        assertFalse(worlds.forget(STEVE), "forgetting twice is a no-op");
        assertNull(new PlayerWorlds(new FlatFileStore(dir)).worldOf(STEVE), "and it stayed forgotten on disk");
    }

    @Test
    void aHandEditedFileLosesOnlyTheLinesItBroke() throws IOException {
        Files.writeString(dir.resolve(PlayerWorlds.TABLE + ".txt"), """
                # a comment

                not-a-uuid=hell
                missing-an-equals
                %s=hell
                %s=
                """.formatted(STEVE, ALEX), StandardCharsets.UTF_8);

        PlayerWorlds worlds = new PlayerWorlds(new FlatFileStore(dir));
        assertEquals("hell", worlds.worldOf(STEVE), "the one good line still reads");
        assertNull(worlds.worldOf(ALEX), "a uuid with no world is not an entry");
        assertEquals(1, worlds.size());
    }
}
