package com.jedrock.core.permission;

import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The set of server operators, by (lower-cased) name, persisted to a plain {@code ops.txt} — one name per
 * line, {@code #} comments and blanks ignored. Deliberately name-based (both editions have a username, and
 * it matches the config style) rather than UUID; a v1 op is simply "has every permission".
 *
 * <p>Loaded once at startup, rewritten on every {@link #add}/{@link #remove}. In-memory reads are cheap and
 * thread-safe (a synchronized {@link LinkedHashSet}); a write also persists. If the file can't be read the
 * list starts empty (and the console is still an op, so an admin can recover).
 */
public final class OpList {

    private static final JLogger LOGGER = JLogger.getLogger(OpList.class);

    private final Path file;
    private final Set<String> ops = Collections.synchronizedSet(new LinkedHashSet<>());

    public OpList(Path file) {
        this.file = file;
        load();
    }

    /** @return whether {@code name} is an operator (case-insensitive). */
    public boolean isOp(String name) {
        return name != null && ops.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Grant op to {@code name}. @return {@code true} if it was newly added (was not already an op). */
    public boolean add(String name) {
        if (name == null || name.isBlank() || !ops.add(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        save();
        return true;
    }

    /** Revoke op from {@code name}. @return {@code true} if it was actually removed. */
    public boolean remove(String name) {
        if (name == null || !ops.remove(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        save();
        return true;
    }

    /** A snapshot of the current operator names, for {@code /op} listing. */
    public Set<String> names() {
        synchronized (ops) {
            return new LinkedHashSet<>(ops);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return; // no ops yet — fine, the console can grant the first
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    ops.add(name.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read ops file " + file + ": " + e + " — starting with no ops");
        }
    }

    private void save() {
        try {
            StringBuilder sb = new StringBuilder("# Jedrock operators — one player name per line.\n");
            synchronized (ops) {
                for (String name : ops) {
                    sb.append(name).append('\n');
                }
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write ops file " + file + ": " + e);
        }
    }
}
