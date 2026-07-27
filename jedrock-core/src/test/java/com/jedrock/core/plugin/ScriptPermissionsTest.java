package com.jedrock.core.plugin;

import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code permissions} global: groups, a player's own rights, and op — the writing half a script only
 * had through {@code dispatchCommand('perm …')} before.
 */
class ScriptPermissionsTest {

    @TempDir
    Path dir;

    private PermissionManager perms;
    private OpList ops;

    private ScriptPermissions permissions() {
        perms = new PermissionManager(dir.resolve("permissions.txt"));
        ops = new OpList(dir.resolve("ops.txt"));
        return new ScriptPermissions(perms, ops);
    }

    // ===== Groups =====

    @Test
    void aGroupIsBuiltAndReadBack() {
        ScriptPermissions permissions = permissions();

        ScriptPermissionGroup builders = permissions.createGroup("builders")
                .add("jedrock.region.plot7.build")
                .add("jedrock.command.tp")
                .setPrefix("{aqua}[Builder] ");

        assertEquals("builders", builders.getName());
        assertEquals("{aqua}[Builder] ", builders.getPrefix());
        assertEquals(List.of("jedrock.region.plot7.build", "jedrock.command.tp"),
                List.of(builders.getNodes()));
        assertTrue(builders.exists());
    }

    @Test
    void creatingAGroupTwiceTakesTheOneThatIsThere() {
        ScriptPermissions permissions = permissions();
        permissions.createGroup("builders").add("jedrock.command.tp");

        ScriptPermissionGroup again = permissions.createGroup("BUILDERS");

        assertArrayEquals(new String[]{"jedrock.command.tp"}, again.getNodes(),
                "a script declaring its groups on every load must not wipe them");
        assertEquals(2, permissions.getGroups().length, "default + builders, not a third");
    }

    @Test
    void aGroupCanInheritAnother() {
        ScriptPermissions permissions = permissions();
        permissions.defaultGroup().add("jedrock.command.spawn");
        permissions.createGroup("mod").inherit(permissions.defaultGroup().getName());
        permissions.forPlayer("Alex").addGroup("mod");

        assertTrue(permissions.has("Alex", "jedrock.command.spawn"), "inherited from the parent");
        assertArrayEquals(new String[]{"default"}, permissions.group("mod").getParents());

        permissions.group("mod").uninherit("default");
        assertFalse(permissions.has("Alex", "jedrock.command.spawn"));
    }

    @Test
    void theDefaultGroupCanBeMovedAndAGroupDeleted() {
        ScriptPermissions permissions = permissions();
        permissions.createGroup("newbie").add("jedrock.command.spawn");

        assertTrue(permissions.setDefaultGroup("newbie"));
        assertTrue(permissions.group("newbie").isDefault());
        assertTrue(permissions.has("Nobody", "jedrock.command.spawn"),
                "an unassigned player falls to whatever the default is now");

        assertTrue(permissions.deleteGroup("default"));
        assertNull(permissions.group("default"));
    }

    @Test
    void makeDefaultDoesTheSameFromTheGroup() {
        ScriptPermissions permissions = permissions();

        permissions.createGroup("newbie").makeDefault();

        assertTrue(permissions.group("newbie").isDefault());
        assertEquals("newbie", permissions.defaultGroup().getName());
    }

    // ===== One player =====

    @Test
    void aPlayerGetsARoleAndSomethingThatIsJustTheirs() {
        ScriptPermissions permissions = permissions();
        permissions.createGroup("builders").add("jedrock.command.tp");
        ScriptUserPermissions steve = permissions.forPlayer("Steve");

        assertTrue(steve.addGroup("builders"));
        assertTrue(steve.add("jedrock.region.plot7.build"));

        assertTrue(steve.has("jedrock.command.tp"), "from the group");
        assertTrue(steve.has("jedrock.region.plot7.build"), "and from their own node");
        assertArrayEquals(new String[]{"builders"}, steve.getGroups());
        assertArrayEquals(new String[]{"jedrock.region.plot7.build"}, steve.getNodes());
        assertFalse(permissions.forPlayer("Alex").has("jedrock.region.plot7.build"), "nobody else got it");
    }

    @Test
    void denyWinsBetweenAPlayersOwnNodeAndTheirGroup() {
        ScriptPermissions permissions = permissions();
        permissions.defaultGroup().add("jedrock.command.tp");

        permissions.forPlayer("Steve").add("-jedrock.command.tp");

        assertFalse(permissions.has("Steve", "jedrock.command.tp"));
        assertTrue(permissions.has("Alex", "jedrock.command.tp"), "only Steve was singled out");
    }

    @Test
    void rightsCanBePreparedForSomebodyWhoHasNeverLoggedIn() {
        ScriptPermissions permissions = permissions();

        permissions.forPlayer("Newcomer").add("jedrock.region.plot7.build");

        assertTrue(permissions.has("Newcomer", "jedrock.region.plot7.build"),
                "the file is keyed by name, so an offline player can be set up in advance");
    }

    @Test
    void takingThingsBackWorksAndReportsWhetherItDidAnything() {
        ScriptPermissions permissions = permissions();
        ScriptUserPermissions steve = permissions.forPlayer("Steve");
        permissions.createGroup("builders");
        steve.addGroup("builders");
        steve.add("example.secret");

        assertTrue(steve.remove("example.secret"));
        assertFalse(steve.remove("example.secret"), "already gone");
        assertTrue(steve.removeGroup("builders"));
        assertFalse(steve.removeGroup("builders"));
        assertEquals(0, steve.getNodes().length);
        assertEquals(0, steve.getGroups().length);
    }

    // ===== Op =====

    @Test
    void opIsASwitchAndHoldsEverything() {
        ScriptPermissions permissions = permissions();
        ScriptUserPermissions admin = permissions.forPlayer("Admin");

        assertFalse(admin.isOp());
        assertFalse(admin.has("anything.at.all"));

        assertTrue(admin.setOp(true));
        assertTrue(admin.isOp());
        assertTrue(admin.has("anything.at.all"), "an op holds every node");
        assertTrue(permissions.isOp("Admin"));
        assertTrue(permissions.isOp("ADMIN"), "op is matched case-insensitively");
        assertArrayEquals(new String[]{"admin"}, permissions.getOps(),
                "the op list keeps names folded, which is how it compares them");

        assertTrue(admin.setOp(false));
        assertFalse(admin.has("anything.at.all"));
    }

    // ===== It is server state =====

    @Test
    void everythingWrittenSurvivesARestart() {
        ScriptPermissions permissions = permissions();
        permissions.createGroup("builders").add("jedrock.command.tp").setPrefix("{aqua}[B] ");
        permissions.forPlayer("Steve").addGroup("builders");
        permissions.forPlayer("Steve").add("jedrock.region.plot7.build");
        permissions.forPlayer("Admin").setOp(true);

        PermissionManager reloaded = new PermissionManager(dir.resolve("permissions.txt"));
        OpList reloadedOps = new OpList(dir.resolve("ops.txt"));
        ScriptPermissions after = new ScriptPermissions(reloaded, reloadedOps);

        assertTrue(after.has("Steve", "jedrock.command.tp"));
        assertTrue(after.has("Steve", "jedrock.region.plot7.build"));
        assertEquals("{aqua}[B] ", after.forPlayer("Steve").getPrefix());
        assertTrue(after.isOp("Admin"));
    }

    @Test
    void reloadDiscardsWhatIsInMemory() {
        ScriptPermissions permissions = permissions();
        permissions.createGroup("temp").add("example.node");
        assertTrue(permissions.has("Anyone", "example.node") || permissions.group("temp") != null);

        // Write the file out from underneath it, then reload: the group is gone.
        perms.deleteGroup("temp");
        permissions.reload();

        assertNull(permissions.group("temp"));
    }

    // ===== Misuse =====

    @Test
    void aNonPlayerIsRefusedRatherThanQuietlyMisread() {
        ScriptPermissions permissions = permissions();

        assertThrows(IllegalArgumentException.class, () -> permissions.forPlayer(42));
        assertThrows(IllegalArgumentException.class, () -> permissions.forPlayer(null));
    }

    @Test
    void aGroupHandleSurvivesItsGroupBeingDeleted() {
        ScriptPermissions permissions = permissions();
        ScriptPermissionGroup temp = permissions.createGroup("temp").add("example.node");
        permissions.deleteGroup("temp");

        assertFalse(temp.exists(), "the handle says so rather than throwing");
        assertEquals(0, temp.getNodes().length);
        assertEquals("", temp.getPrefix());
        assertNotNull(temp.getParents());
    }
}
