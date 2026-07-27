package com.jedrock.core.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission system: default group, wildcards, {@code -node} deny, inheritance, prefix, per-player
 * nodes, and persistence.
 */
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

    // ===== Per-player nodes =====
    //
    // Groups answer "what may this kind of player do". These answer the cases that are genuinely about one
    // person — the owner of one plot — without inventing a throwaway group per player.

    @Test
    void aNodeCanBeGrantedToOnePlayerWithoutAGroup() {
        PermissionManager perms = manager();

        assertTrue(perms.addUserPermission("Steve", "jedrock.region.plot7.build"));
        assertFalse(perms.addUserPermission("Steve", "jedrock.region.plot7.build"), "already had it");

        assertTrue(perms.has("Steve", "jedrock.region.plot7.build"));
        assertFalse(perms.has("Alex", "jedrock.region.plot7.build"), "and nobody else got it");
    }

    @Test
    void aPlayersOwnWildcardWorksLikeAGroupsDoes() {
        PermissionManager perms = manager();
        perms.addUserPermission("Steve", "jedrock.region.plot7.*");

        assertTrue(perms.has("Steve", "jedrock.region.plot7.build"));
        assertTrue(perms.has("Steve", "jedrock.region.plot7.entry"));
        assertFalse(perms.has("Steve", "jedrock.region.spawn.build"), "a different region is a different node");
    }

    @Test
    void denyWinsBetweenAPlayersOwnNodesAndTheirGroups() {
        PermissionManager perms = manager();
        perms.addGroupPermission(perms.defaultGroup().name(), "jedrock.region.spawn.build");

        perms.addUserPermission("Steve", "-jedrock.region.spawn.build");

        assertFalse(perms.has("Steve", "jedrock.region.spawn.build"),
                "a player-level deny beats a group grant, the same way it does inside a group");
        assertTrue(perms.has("Alex", "jedrock.region.spawn.build"), "the group still grants it to everyone else");
    }

    @Test
    void aPlayersOwnNodesSurviveARestartAndCanBeTakenBack() {
        Path file = dir.resolve("permissions.txt");
        PermissionManager first = new PermissionManager(file);
        first.addUserPermission("Steve", "jedrock.region.plot7.build");
        first.addUserGroup("Steve", first.defaultGroup().name());

        PermissionManager reloaded = new PermissionManager(file);
        assertTrue(reloaded.has("Steve", "jedrock.region.plot7.build"));
        assertEquals(java.util.Set.of("jedrock.region.plot7.build"), reloaded.userPermissions("Steve"));

        assertTrue(reloaded.removeUserPermission("Steve", "jedrock.region.plot7.build"));
        assertFalse(reloaded.has("Steve", "jedrock.region.plot7.build"));
        assertFalse(new PermissionManager(file).has("Steve", "jedrock.region.plot7.build"),
                "the removal was written out too");
    }

    @Test
    void aPlayerWithOnlyOwnNodesStillGetsWrittenOut() {
        Path file = dir.resolve("permissions.txt");
        PermissionManager first = new PermissionManager(file);
        first.addUserPermission("Steve", "jedrock.command.fly"); // no group membership at all

        assertTrue(new PermissionManager(file).has("Steve", "jedrock.command.fly"),
                "a user block is written for nodes, not only for memberships");
    }
}
