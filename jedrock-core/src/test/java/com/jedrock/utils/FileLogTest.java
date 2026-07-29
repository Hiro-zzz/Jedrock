package com.jedrock.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log file: that it gets written, that a run rotates the last one out, and that history is bounded. */
class FileLogTest {

    @TempDir
    Path logs;

    @AfterEach
    void restoreConsoleLogging() {
        FileLog.close();
    }

    @Test
    void everyLevelReachesTheFile() throws IOException {
        FileLog.install(logs, 5);
        JLogger log = JLogger.getLogger("Test");
        log.info("an informative line");
        log.warn("a warning");
        log.error("something broke");
        FileLog.close();

        String written = Files.readString(logs.resolve("latest.log"), StandardCharsets.UTF_8);
        assertTrue(written.contains("[Test/INFO] an informative line"), written);
        assertTrue(written.contains("[Test/WARN] a warning"), written);
        assertTrue(written.contains("[Test/ERROR] something broke"), written);
    }

    @Test
    void aLoggerMadeBeforeTheFileExistedStillWritesToIt() throws IOException {
        // The case this whole design exists for: nearly every logger in the codebase is a static field,
        // created when its class loads — long before the server has decided there should be a log at all.
        JLogger early = JLogger.getLogger("Early");
        early.info("before");

        FileLog.install(logs, 5);
        early.info("after");
        FileLog.close();

        String written = Files.readString(logs.resolve("latest.log"), StandardCharsets.UTF_8);
        assertTrue(written.contains("after"), "a logger held since startup must follow the swap");
    }

    @Test
    void aRunRotatesThePreviousOneOutOfTheWay() throws IOException {
        FileLog.install(logs, 5);
        JLogger.getLogger("Run1").info("the first run");
        FileLog.close();
        // The archive is named for when the file was last written, so give the two runs distinct stamps.
        Files.setLastModifiedTime(logs.resolve("latest.log"),
                FileTime.from(Instant.now().minusSeconds(3600)));

        FileLog.install(logs, 5);
        JLogger.getLogger("Run2").info("the second run");
        FileLog.close();

        assertTrue(Files.readString(logs.resolve("latest.log")).contains("the second run"),
                "latest.log is always the current run");
        List<Path> archived = archivedLogs();
        assertEquals(1, archived.size(), "and the run before it is beside it");
        assertTrue(Files.readString(archived.get(0)).contains("the first run"));
    }

    @Test
    void theHistoryIsBounded() throws IOException {
        for (int run = 1; run <= 5; run++) {
            FileLog.install(logs, 2);
            JLogger.getLogger("Run").info("run " + run);
            FileLog.close();
            Files.setLastModifiedTime(logs.resolve("latest.log"),
                    FileTime.from(Instant.now().minusSeconds(3600L * (10 - run))));
        }
        assertEquals(2, archivedLogs().size(), "keep-files is a ceiling on the history, not a suggestion");
    }

    @Test
    void aFolderThatCannotBeOpenedLeavesTheConsoleWorking() throws IOException {
        // A file where the folder should be: install can't succeed, and must not throw either.
        Path blocked = logs.resolve("not-a-folder");
        Files.writeString(blocked, "in the way", StandardCharsets.UTF_8);

        FileLog.install(blocked, 5);
        JLogger.getLogger("Test").info("still logging"); // console-only, but it must not blow up
    }

    private List<Path> archivedLogs() throws IOException {
        try (var files = Files.list(logs)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals("latest.log"))
                    .toList();
        }
    }
}
