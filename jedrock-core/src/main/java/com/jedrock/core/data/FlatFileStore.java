package com.jedrock.core.data;

import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default {@link DataStore}: one {@code key=value} text file per table in {@code data/}.
 *
 * <p>It writes what the server already wrote, deliberately. That is what makes the storage layer free to
 * adopt — no migration, no new format, and the files stay the thing an administrator can open in an
 * editor and fix at two in the morning. A comment header says which they are.
 *
 * <p>Lines are {@code key=value}; {@code #} starts a comment; a line without an {@code =} is skipped with
 * a warning rather than failing the load, because one hand-edited line should cost one entry and not the
 * whole file.
 */
public final class FlatFileStore implements DataStore {

    private static final JLogger LOGGER = JLogger.getLogger("Storage");

    private final Path folder;

    public FlatFileStore(Path folder) {
        this.folder = folder;
    }

    @Override
    public String describe() {
        return "files in " + folder.toAbsolutePath().normalize();
    }

    @Override
    public synchronized Map<String, String> load(String table) {
        Map<String, String> rows = new LinkedHashMap<>();
        Path file = fileOf(table);
        if (!Files.isRegularFile(file)) {
            return rows; // never written — an empty table, not an error
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                int split = entry.indexOf('=');
                if (split <= 0) {
                    LOGGER.warn("Skipping a line of " + file.getFileName() + " that isn't key=value: " + entry);
                    continue;
                }
                rows.put(entry.substring(0, split).strip(), entry.substring(split + 1).strip());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read " + file.toAbsolutePath() + ": " + e
                    + " — treating it as empty and leaving the file alone");
        }
        return rows;
    }

    @Override
    public synchronized void save(String table, Map<String, String> rows) {
        Path file = fileOf(table);
        StringBuilder sb = new StringBuilder("# Jedrock: " + table + " — key=value, one per line.\n"
                + "# Written by the server; safe to edit while it is stopped.\n");
        for (Map.Entry<String, String> row : rows.entrySet()) {
            sb.append(row.getKey()).append('=').append(row.getValue()).append('\n');
        }
        try {
            Files.createDirectories(folder);
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write " + file.toAbsolutePath() + ": " + e);
        }
    }

    @Override
    public void close() {
        // Nothing is held open — which is most of the argument for this being the default.
    }

    private Path fileOf(String table) {
        return folder.resolve(table + ".txt");
    }
}
