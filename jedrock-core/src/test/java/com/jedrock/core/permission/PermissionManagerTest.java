package com.jedrock.core.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The group permission system: default group, wildcards, {@code -node} deny, inheritance, prefix, persistence. */
class PermissionManagerTest {

    @TempDir
    Path dir;

    private PermissionManager manager() {
        return new PermissionManager(dir.resolve("permissions.txt"));
    }

    @Test
    void seedsADefaultGroupWhenFileAbsent() {
        PermissionManager perms = manager();
        assertNotNull(perms.defaultGroup(), "a default group is always present");
    }

    @Test
    void aPlayerWithNoAssignmentFallsToTheDefaultGroup() {
        PermissionManager perms = manager();
        perms.addGroupPermission(perms.defaultGroup().name(), "jedrock.command.spawn");
        assertTrue(perms.has("Steve", "jedrock.command.spawn"), "resolved via the default group");
        assertFalse(perms.has("Steve", "jedrock.command.tp"));
    }

    @Test
    void wildcardsGrantSubtreesAndEverything() {
        PermissionManager perms = manager();
        perms.createGroup("mod");
        perms.addGroupPermission("mod", "jedrock.command.*");
        perms.addUserGroup("Alex", "mod");
        assertTrue(perms.has("Alex", "jedrock.command.tp"));
        assertTrue(perms.has("Alex", "jedrock.command.kick"));
        assertFalse(perms.has("Alex", "other.node"), "the subtree wildcard doesn't leak");

        perms.createGroup("admin");
        perms.addGroupPermission("admin", "*");
        perms.addUserGroup("Root", "admin");
        assertTrue(perms.has("Root", "anything.at.all"));
    }

    @Test
    void explicitDenyBeatsAGrant() {
        PermissionManager perms = manager();
        perms.createGroup("mod");
        perms.addGroupPermission("mod", "jedrock.command.*");
        perms.addGroupPermission("mod", "-jedrock.command.stop");
        perms.addUserGroup("Alex", "mod");
        assertTrue(perms.has("Alex", "jedrock.command.tp"));
        assertFalse(perms.has("Alex", "jedrock.command.stop"), "the -node deny wins over the wildcard grant");
    }

    @Test
    void inheritanceUnionsParentNodes() {
        PermissionManager perms = manager();
        perms.createGroup("base");
        perms.addGroupPermission("base", "jedrock.command.help");
        perms.createGroup("mod");
        perms.addGroupPermission("mod", "jedrock.command.kick");
        perms.addGroupParent("mod", "base");
        perms.addUserGroup("Alex", "mod");
        assertTrue(perms.has("Alex", "jedrock.command.kick"), "own node");
        assertTrue(perms.has("Alex", "jedrock.command.help"), "inherited node");
    }

    @Test
    void anAssignedGroupReplacesTheDefault() {
        PermissionManager perms = manager();
        perms.addGroupPermission(perms.defaultGroup().name(), "default.only");
        perms.createGroup("mod");
        perms.addGroupPermission("mod", "mod.only");
        perms.addUserGroup("Alex", "mod");
        assertTrue(perms.has("Alex", "mod.only"));
        assertFalse(perms.has("Alex", "default.only"), "an assigned player no longer falls to the default group");
    }

    @Test
    void prefixResolvesFromTheGroup() {
        PermissionManager perms = manager();
        perms.createGroup("admin");
        perms.setGroupPrefix("admin", "{red}[Admin] ");
        perms.addUserGroup("Root", "admin");
        assertEquals("{red}[Admin] ", perms.prefixOf("Root"));
        assertEquals("", perms.prefixOf("Nobody"), "no prefix without a prefixed group");
    }

    @Test
    void inheritanceCycleIsSafe() {
        PermissionManager perms = manager();
        perms.createGroup("a");
        perms.createGroup("b");
        perms.addGroupParent("a", "b");
        perms.addGroupParent("b", "a"); // cycle
        perms.addGroupPermission("a", "node.a");
        perms.addGroupPermission("b", "node.b");
        perms.addUserGroup("X", "a");
        assertTrue(perms.has("X", "node.a"));
        assertTrue(perms.has("X", "node.b"), "cycle is expanded once, not infinitely");
    }

    @Test
    void survivesAReload() {
        Path file = dir.resolve("permissions.txt");
        PermissionManager first = new PermissionManager(file);
        first.createGroup("mod");
        first.addGroupPermission("mod", "jedrock.command.*");
        first.addGroupPermission("mod", "-jedrock.command.stop");
        first.addGroupParent("mod", first.defaultGroup().name());
        first.setGroupPrefix("mod", "{aqua}[Mod] ");
        first.addUserGroup("Alex", "mod");

        PermissionManager reloaded = new PermissionManager(file);
        assertTrue(reloaded.has("Alex", "jedrock.command.tp"));
        assertFalse(reloaded.has("Alex", "jedrock.command.stop"));
        assertEquals("{aqua}[Mod] ", reloaded.prefixOf("Alex"));
        assertEquals(java.util.Set.of("mod"), reloaded.userGroups("Alex"));
    }
}
