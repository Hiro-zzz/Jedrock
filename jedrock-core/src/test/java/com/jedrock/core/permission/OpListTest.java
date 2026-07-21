package com.jedrock.core.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The operator list: case-insensitive membership, idempotent add/remove, and persistence across loads. */
class OpListTest {

    @TempDir
    Path dir;

    @Test
    void addIsCaseInsensitiveAndIdempotent() {
        OpList ops = new OpList(dir.resolve("ops.txt"));
        assertFalse(ops.isOp("Steve"));
        assertTrue(ops.add("Steve"), "first grant is new");
        assertFalse(ops.add("steve"), "same name (any case) is not a new grant");
        assertTrue(ops.isOp("STEVE"), "membership ignores case");
    }

    @Test
    void removeReportsWhetherItActuallyRemoved() {
        OpList ops = new OpList(dir.resolve("ops.txt"));
        ops.add("Alex");
        assertTrue(ops.remove("alex"));
        assertFalse(ops.isOp("Alex"));
        assertFalse(ops.remove("alex"), "removing a non-op is a no-op");
    }

    @Test
    void survivesAReload() {
        Path file = dir.resolve("ops.txt");
        OpList first = new OpList(file);
        first.add("Notch");
        first.add("Herobrine");

        OpList reloaded = new OpList(file); // reads the file written by the first
        assertTrue(reloaded.isOp("notch"));
        assertTrue(reloaded.isOp("herobrine"));
        assertFalse(reloaded.isOp("someone_else"));
    }

    @Test
    void blankNameIsNeverAnOp() {
        OpList ops = new OpList(dir.resolve("ops.txt"));
        assertFalse(ops.add("  "));
        assertFalse(ops.add(null));
        assertFalse(ops.isOp(null));
    }
}
