package com.jedrock.core.data;

import com.jedrock.api.config.ServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage layer: the file backend it defaults to, and — more importantly — that asking for a database
 * it cannot have gets you a running server rather than a stack trace.
 */
class DataStoreTest {

    @TempDir
    Path home;

    @Test
    void theFileBackendRoundTripsATable() {
        FlatFileStore store = new FlatFileStore(home);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("11111111-2222-3333-4444-555555555555", "hell");
        rows.put("66666666-7777-8888-9999-aaaaaaaaaaaa", "arena");
        store.save("player-worlds", rows);

        assertEquals(rows, new FlatFileStore(home).load("player-worlds"), "and across a restart");
    }

    @Test
    void savingIsAReplaceNotAMerge() {
        FlatFileStore store = new FlatFileStore(home);
        store.save("t", Map.of("a", "1"));
        store.save("t", Map.of("b", "2"));

        Map<String, String> after = store.load("t");
        assertEquals(Map.of("b", "2"), after, "deleting a key means saving without it");
    }

    @Test
    void anUnwrittenTableIsEmptyRatherThanAFailure() {
        assertTrue(new FlatFileStore(home).load("never-written").isEmpty());
    }

    @Test
    void theFileItWritesIsTheOneTheServerAlwaysWrote() throws IOException {
        new FlatFileStore(home).save("player-worlds", Map.of("steve", "hell"));

        String written = Files.readString(home.resolve("player-worlds.txt"), StandardCharsets.UTF_8);
        assertTrue(written.contains("steve=hell"), written);
        assertTrue(written.startsWith("#"), "with a header saying what it is: " + written);
    }

    @Test
    void aHandEditedLineCostsOneEntryAndNotTheFile() throws IOException {
        Files.writeString(home.resolve("t.txt"), """
                # a comment

                good=value
                this line has no equals sign
                another=one
                """, StandardCharsets.UTF_8);

        Map<String, String> rows = new FlatFileStore(home).load("t");
        assertEquals(2, rows.size());
        assertEquals("value", rows.get("good"));
        assertEquals("one", rows.get("another"));
    }

    @Test
    void aDatabaseThatCannotBeOpenedFallsBackToFiles() {
        // No driver in libs/ — the most likely way this is misconfigured, since none is bundled.
        ServerProperties.Storage config = new ServerProperties.Storage(
                "jdbc", "jdbc:sqlite:data/jedrock.db", "org.sqlite.JDBC", "", "");

        DataStore store = DataStores.open(home, home.resolve("data"), config);

        assertInstanceOf(FlatFileStore.class, store,
                "a missing driver is a warning and a fallback, never a server that won't start");
        store.save("t", Map.of("k", "v"));
        assertEquals("v", store.load("t").get("k"), "and it works, which is the point of falling back");
    }

    @Test
    void aBlankUrlIsNotADatabase() {
        ServerProperties.Storage config = new ServerProperties.Storage("jdbc", "  ", "org.sqlite.JDBC", "", "");
        assertInstanceOf(FlatFileStore.class, DataStores.open(home, home.resolve("data"), config));
    }

    @Test
    void theDefaultBackendIsFiles() {
        DataStore store = DataStores.open(home, home.resolve("data"), ServerProperties.Storage.defaults());
        assertInstanceOf(FlatFileStore.class, store);
        assertTrue(store.describe().contains("data"), store.describe());
    }
}
