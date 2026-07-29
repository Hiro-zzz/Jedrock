package com.jedrock.core.config;

import com.jedrock.api.config.ServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The folders a first run makes, the names it takes from config, and what it does with an older install. */
class ServerLayoutTest {

    @TempDir
    Path home;

    @Test
    void firstRunMakesTheFourFolders() {
        ServerLayout layout = ServerLayout.defaults(home);

        assertTrue(Files.isDirectory(layout.worlds()));
        assertTrue(Files.isDirectory(layout.plugins()));
        assertTrue(Files.isDirectory(layout.logs()));
        assertTrue(Files.isDirectory(layout.data()));
        assertEquals(home.resolve("worlds").resolve("hell"), layout.worldFolder("hell"));
        assertEquals(home.resolve("data").resolve("ops.txt"), layout.dataFile("ops.txt"));
    }

    @Test
    void theFolderNamesComeFromTheConfig() {
        ServerProperties config = withPaths(new ServerProperties.Paths("levels", "js", "log", "state"));
        ServerLayout layout = ServerLayout.prepare(home, config);

        assertTrue(Files.isDirectory(home.resolve("levels")));
        assertTrue(Files.isDirectory(home.resolve("js")));
        assertEquals(home.resolve("state").resolve("ops.txt"), layout.dataFile("ops.txt"));
    }

    @Test
    void aFolderNameThatIsTheRootItselfFallsBack() {
        ServerProperties config = withPaths(new ServerProperties.Paths(".", "plugins", "logs", "data"));
        ServerLayout layout = ServerLayout.prepare(home, config);

        assertEquals(home.resolve("worlds"), layout.worlds(), "'.' would put level files loose in the root");
    }

    @Test
    void anOlderFlatInstallIsMovedIntoPlace() throws IOException {
        // What a pre-layout server left behind: world folders beside the jar, and its bookkeeping loose.
        Files.createDirectories(home.resolve("world"));
        Files.writeString(home.resolve("world").resolve("level.jdw"), "terrain", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("hell"));
        Files.writeString(home.resolve("hell").resolve("level.jdw"), "nether", StandardCharsets.UTF_8);
        Files.writeString(home.resolve("ops.txt"), "steve\n", StandardCharsets.UTF_8);
        Files.writeString(home.resolve("player-worlds.txt"), "# nobody\n", StandardCharsets.UTF_8);
        // A folder that is not a world is not touched, whatever it is called.
        Files.createDirectories(home.resolve("notes"));

        ServerLayout layout = ServerLayout.defaults(home);

        assertEquals("terrain", Files.readString(layout.worldFolder("world").resolve("level.jdw")));
        assertEquals("nether", Files.readString(layout.worldFolder("hell").resolve("level.jdw")));
        assertEquals("steve\n", Files.readString(layout.dataFile("ops.txt")));
        assertTrue(Files.exists(layout.dataFile("player-worlds.txt")));
        assertFalse(Files.exists(home.resolve("world")), "the old folder is moved, not copied");
        assertTrue(Files.isDirectory(home.resolve("notes")), "a folder with no level file is left alone");
    }

    @Test
    void migrationNeverOverwritesWhatIsAlreadyThere() throws IOException {
        Files.createDirectories(home.resolve("worlds").resolve("world"));
        Files.writeString(home.resolve("worlds").resolve("world").resolve("level.jdw"), "new",
                StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("world"));
        Files.writeString(home.resolve("world").resolve("level.jdw"), "old", StandardCharsets.UTF_8);

        ServerLayout layout = ServerLayout.defaults(home);

        assertEquals("new", Files.readString(layout.worldFolder("world").resolve("level.jdw")),
                "two worlds claiming one name is a question for a person");
        assertEquals("old", Files.readString(home.resolve("world").resolve("level.jdw")),
                "and the old copy is left where it is, not deleted");
    }

    @Test
    void runningTwiceChangesNothing() throws IOException {
        Files.createDirectories(home.resolve("world"));
        Files.writeString(home.resolve("world").resolve("level.jdw"), "terrain", StandardCharsets.UTF_8);

        ServerLayout.defaults(home);
        ServerLayout layout = ServerLayout.defaults(home); // the second boot

        assertEquals("terrain", Files.readString(layout.worldFolder("world").resolve("level.jdw")));
        assertFalse(Files.exists(home.resolve("world")));
    }

    private static ServerProperties withPaths(ServerProperties.Paths paths) {
        ServerProperties d = ServerProperties.defaults();
        return new ServerProperties(d.name(), d.bindHost(), d.javaPort(), d.bedrockPort(), d.maxPlayers(),
                d.motd(), d.seed(), d.tickRate(), d.viewDistance(), d.judgeEnabled(), d.maxReach(),
                d.maxMoveDelta(), d.bedrock014Port(), d.bedrock014Enabled(), d.defaultGameMode(),
                d.peSidebarRaise(), d.peSidebarShift(), d.rememberWorld(), paths, d.worlds(), d.plugins(),
                d.logging(), d.rcon(), d.storage());
    }
}
