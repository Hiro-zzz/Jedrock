package com.jedrock.core.region;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.BlockPlaceEvent;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.DamageCause;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerRegionEnterEvent;
import com.jedrock.api.event.player.PlayerRegionLeaveEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.region.Region;
import com.jedrock.api.region.RegionFlag;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regions: the box itself, the deny-wins overlap rule, the flags being enforced through the events the
 * core already routes decisions through, the crossing detection on the movement path, and the file.
 */
class RegionManagerTest {

    private final EventBus events = new EventBus();
    private final RegionManager regions = new RegionManager(events);
    private final CoreWorld world = new CoreWorld("rg", Dimension.OVERWORLD, 1L);

    private CorePlayer player() {
        return new CorePlayer(UUID.randomUUID(), "P", new Conn(), world, world.getSpawnLocation(),
                GameMode.SURVIVAL);
    }

    private static void standAt(CorePlayer player, double x, double y, double z) {
        player.setLocation(new Location(player.getWorld(), x, y, z, 0f, 0f));
    }

    /** A player whose permissions actually resolve — needed for anything about exemptions. */
    private CorePlayer permissioned(String name, Path dir, PermissionManager perms) {
        CorePlayer player = new CorePlayer(UUID.randomUUID(), name, new Conn(), world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        player.setPermissions(new OpList(dir.resolve("ops.txt")), perms);
        return player;
    }

    // ===== The box =====

    @Test
    void cornersAreNormalizedSoEitherOrderDescribesTheSameBox() {
        Region region = regions.create("box", 10, 70, 10, 0, 60, 0);

        assertEquals(0, region.getMinX());
        assertEquals(60, region.getMinY());
        assertEquals(10, region.getMaxZ());
        assertEquals(11L * 11L * 11L, region.getVolume(), "bounds are inclusive at both ends");
    }

    @Test
    void containmentIsInclusiveAndFloorsAFractionalPosition() {
        Region region = regions.create("box", 0, 60, 0, 10, 70, 10);

        assertTrue(region.contains(0, 60, 0), "the low corner is inside");
        assertTrue(region.contains(10, 70, 10), "and so is the high one");
        assertTrue(region.contains(10.9, 70.5, 10.2), "standing on block 10 is being in block 10");
        assertFalse(region.contains(11, 70, 10));
        assertFalse(region.contains(-0.5, 65, 5), "which floors to -1, outside");
    }

    @Test
    void aNameIsTakenOnlyOnceSoNothingSilentlyLosesItsFlags() {
        assertNotNull(regions.create("spawn", 0, 0, 0, 1, 1, 1));

        assertNull(regions.create("SPAWN", 5, 5, 5, 6, 6, 6), "matched case-insensitively");
        assertEquals(1, regions.size());
        assertNotNull(regions.get("sPaWn"), "and looked up the same way");
    }

    @Test
    void removingOneLeavesTheRest() {
        regions.create("a", 0, 0, 0, 1, 1, 1);
        regions.create("b", 5, 5, 5, 6, 6, 6);

        assertTrue(regions.remove("a"));
        assertFalse(regions.remove("a"), "already gone");
        assertEquals(1, regions.size());
        assertNull(regions.get("a"));
    }

    // ===== Overlap =====

    @Test
    void everythingIsAllowedUntilSomethingIsDenied() {
        regions.create("plain", 0, 60, 0, 10, 70, 10);

        for (RegionFlag flag : RegionFlag.values()) {
            assertTrue(regions.allows(5, 65, 5, flag), flag + " starts allowed");
        }
    }

    @Test
    void denyWinsWhereRegionsOverlap() {
        regions.create("outer", 0, 0, 0, 100, 100, 100);         // allows building
        regions.create("inner", 40, 40, 40, 60, 60, 60).deny(RegionFlag.BUILD);

        assertTrue(regions.allows(10, 10, 10, RegionFlag.BUILD), "only the permissive region covers this");
        assertFalse(regions.allows(50, 50, 50, RegionFlag.BUILD), "both cover this, and one says no");
        assertTrue(regions.allows(200, 50, 50, RegionFlag.BUILD), "no region has an opinion out here");
    }

    // ===== Enforcement =====

    @Test
    void aNoBuildRegionCancelsBreakingAndPlacingInsideItOnly() {
        regions.create("keep", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        CorePlayer player = player();

        assertTrue(events.post(new BlockBreakEvent(player, 5, 65, 5, 1)).isCancelled());
        assertTrue(events.post(new BlockPlaceEvent(player, 5, 65, 5, 1, 0)).isCancelled());
        assertFalse(events.post(new BlockBreakEvent(player, 50, 65, 5, 1)).isCancelled(),
                "outside the box the region has nothing to say");
    }

    @Test
    void aNoInteractRegionCancelsRightClicks() {
        regions.create("shop", 0, 60, 0, 10, 70, 10).deny(RegionFlag.INTERACT);
        CorePlayer player = player();

        assertTrue(events.post(new PlayerInteractBlockEvent(player, 5, 65, 5, 54)).isCancelled());
        assertFalse(events.post(new BlockBreakEvent(player, 5, 65, 5, 1)).isCancelled(),
                "interact and build are separate rules");
    }

    @Test
    void pvpAndDamageAreJudgedWhereTheVictimStands() {
        regions.create("safe", 0, 60, 0, 10, 70, 10).deny(RegionFlag.PVP);
        CorePlayer player = player();
        standAt(player, 5, 65, 5);

        assertTrue(events.post(new PlayerDamageEvent(player, DamageCause.ATTACK, 2)).isCancelled(),
                "no fighting here");
        assertFalse(events.post(new PlayerDamageEvent(player, DamageCause.FALL, 2)).isCancelled(),
                "but gravity still applies — pvp is not a safe zone");

        standAt(player, 50, 65, 5);
        assertFalse(events.post(new PlayerDamageEvent(player, DamageCause.ATTACK, 2)).isCancelled(),
                "step out and it is a fair fight again");
    }

    @Test
    void aNoDamageRegionStopsEverySource() {
        regions.create("lobby", 0, 60, 0, 10, 70, 10).deny(RegionFlag.DAMAGE);
        CorePlayer player = player();
        standAt(player, 5, 65, 5);

        assertTrue(events.post(new PlayerDamageEvent(player, DamageCause.FALL, 5)).isCancelled());
        assertTrue(events.post(new PlayerDamageEvent(player, DamageCause.VOID, 4)).isCancelled());
        assertTrue(events.post(new PlayerDamageEvent(player, DamageCause.ATTACK, 2)).isCancelled());
    }

    @Test
    void enforcementExistsOnlyWhileRegionsDo() {
        assertFalse(events.hasListeners(BlockBreakEvent.class),
                "a server with no regions must not make every block edit build an event");

        regions.create("a", 0, 0, 0, 1, 1, 1);
        assertTrue(events.hasListeners(BlockBreakEvent.class), "the rules are live once there is a region");

        regions.create("b", 5, 5, 5, 6, 6, 6);
        regions.remove("a");
        assertTrue(events.hasListeners(BlockBreakEvent.class), "still one left");

        regions.remove("b");
        assertFalse(events.hasListeners(BlockBreakEvent.class), "and gone again with the last region");
    }

    // ===== Exceptions =====
    //
    // Per-player and per-group, carried by the permission system rather than a roster on the region: the
    // question "may this player do this" already has a whole subsystem, with groups, wildcards and deny.

    @Test
    void aPlayerHoldingTheBypassNodeIsExemptFromThatDenial(@TempDir Path dir) {
        regions.create("plot7", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        PermissionManager perms = new PermissionManager(dir.resolve("permissions.txt"));
        CorePlayer owner = permissioned("Owner", dir, perms);
        CorePlayer stranger = permissioned("Stranger", dir, perms);
        perms.addUserPermission("Owner", "jedrock.region.plot7.build");

        assertFalse(events.post(new BlockBreakEvent(owner, 5, 65, 5, 1)).isCancelled(),
                "the owner may build in their own plot");
        assertTrue(events.post(new BlockBreakEvent(stranger, 5, 65, 5, 1)).isCancelled(),
                "everybody else still can't");
    }

    @Test
    void aGroupCarriesTheExemptionToEveryoneInIt(@TempDir Path dir) {
        regions.create("plot7", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        PermissionManager perms = new PermissionManager(dir.resolve("permissions.txt"));
        perms.createGroup("builders");
        perms.addGroupPermission("builders", "jedrock.region.plot7.build");
        perms.addUserGroup("Alex", "builders");
        CorePlayer alex = permissioned("Alex", dir, perms);

        assertFalse(events.post(new BlockBreakEvent(alex, 5, 65, 5, 1)).isCancelled());
    }

    @Test
    void anExemptionIsPerRegionAndPerFlag(@TempDir Path dir) {
        CoreRegion plot = regions.create("plot7", 0, 60, 0, 10, 70, 10);
        plot.deny(RegionFlag.BUILD);
        plot.deny(RegionFlag.ENTRY);
        regions.create("spawn", 100, 60, 100, 110, 70, 110).deny(RegionFlag.BUILD);
        PermissionManager perms = new PermissionManager(dir.resolve("permissions.txt"));
        CorePlayer steve = permissioned("Steve", dir, perms);
        perms.addUserPermission("Steve", "jedrock.region.plot7.build");

        assertFalse(events.post(new BlockBreakEvent(steve, 5, 65, 5, 1)).isCancelled(), "the flag they hold");
        assertTrue(events.post(new BlockBreakEvent(steve, 105, 65, 105, 1)).isCancelled(),
                "a different region is a different node");
        assertFalse(regions.updateMembership(steve, 5, 65, 5),
                "and being allowed to build is not being allowed through the wall");
    }

    @Test
    void aWholeRegionWildcardCoversEveryFlagIncludingTheWall(@TempDir Path dir) {
        CoreRegion staff = regions.create("staffroom", 0, 60, 0, 10, 70, 10);
        staff.deny(RegionFlag.ENTRY);
        staff.deny(RegionFlag.BUILD);
        PermissionManager perms = new PermissionManager(dir.resolve("permissions.txt"));
        CorePlayer keeper = permissioned("Keeper", dir, perms);
        perms.addUserPermission("Keeper", "jedrock.region.staffroom.*");

        assertTrue(regions.updateMembership(keeper, 5, 65, 5), "the door opens for them");
        assertFalse(events.post(new BlockBreakEvent(keeper, 5, 65, 5, 1)).isCancelled());
    }

    @Test
    void anOpIsExemptEverywhereWithoutBeingNamed(@TempDir Path dir) throws Exception {
        regions.create("spawn", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        Path opsFile = dir.resolve("ops.txt");
        java.nio.file.Files.writeString(opsFile, "Admin\n");
        OpList ops = new OpList(opsFile);
        CorePlayer admin = new CorePlayer(UUID.randomUUID(), "Admin", new Conn(), world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        admin.setPermissions(ops, new PermissionManager(dir.resolve("permissions.txt")));

        assertFalse(events.post(new BlockBreakEvent(admin, 5, 65, 5, 1)).isCancelled(),
                "an op holds every node — the same rule that governs commands");
    }

    @Test
    void theWorldLevelQueryIgnoresExemptionsEntirely(@TempDir Path dir) {
        regions.create("plot7", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        PermissionManager perms = new PermissionManager(dir.resolve("permissions.txt"));
        CorePlayer owner = permissioned("Owner", dir, perms);
        perms.addUserPermission("Owner", "jedrock.region.plot7.build");

        assertFalse(regions.allows(5, 65, 5, RegionFlag.BUILD), "the rule as the world states it");
        assertTrue(regions.allows(owner, 5, 65, 5, RegionFlag.BUILD), "and as it applies to this player");
    }

    @Test
    void aNameThatWouldMakeAnAmbiguousNodeIsRefused() {
        assertNull(regions.create("my.plot", 0, 0, 0, 1, 1, 1), "a dot would invent a wildcard level");
        assertNull(regions.create("my plot", 0, 0, 0, 1, 1, 1), "a space would make the node untypeable");
        assertNull(regions.create("", 0, 0, 0, 1, 1, 1));
        assertNotNull(regions.create("my_plot-7", 0, 0, 0, 1, 1, 1), "letters, digits, _ and - are fine");
    }

    // ===== Crossings =====

    @Test
    void enteringAndLeavingFireOncePerCrossing() {
        regions.create("zone", 0, 60, 0, 10, 70, 10);
        List<String> log = new ArrayList<>();
        events.register(PlayerRegionEnterEvent.class, e -> log.add("enter " + e.getRegion().getName()));
        events.register(PlayerRegionLeaveEvent.class, e -> log.add("leave " + e.getRegion().getName()));
        CorePlayer player = player();

        assertTrue(regions.updateMembership(player, 50, 65, 5), "starting outside");
        assertEquals(List.of(), log);

        assertTrue(regions.updateMembership(player, 5, 65, 5));
        assertEquals(List.of("enter zone"), log);

        assertTrue(regions.updateMembership(player, 6, 65, 6), "still inside");
        assertTrue(regions.updateMembership(player, 7, 65, 7));
        assertEquals(List.of("enter zone"), log, "walking around inside is not a crossing");

        assertTrue(regions.updateMembership(player, 50, 65, 5));
        assertEquals(List.of("enter zone", "leave zone"), log);
    }

    @Test
    void overlappingRegionsEachGetTheirOwnCrossing() {
        regions.create("outer", 0, 0, 0, 100, 100, 100);
        regions.create("inner", 40, 40, 40, 60, 60, 60);
        List<String> entered = new ArrayList<>();
        events.register(PlayerRegionEnterEvent.class, e -> entered.add(e.getRegion().getName()));
        CorePlayer player = player();

        regions.updateMembership(player, 50, 50, 50); // lands in both at once

        assertEquals(List.of("outer", "inner"), entered);
    }

    @Test
    void aDeniedEntryFlagRefusesTheStep() {
        regions.create("wall", 0, 60, 0, 10, 70, 10).deny(RegionFlag.ENTRY);
        CorePlayer player = player();

        assertFalse(regions.updateMembership(player, 5, 65, 5), "the caller is told to put them back");
        assertEquals(0, player.getRegionMembership().inside().length, "and they were never a member");
        assertTrue(regions.updateMembership(player, 50, 65, 5), "outside is still fine");
    }

    @Test
    void cancellingTheEnterEventRefusesTheStepToo() {
        regions.create("zone", 0, 60, 0, 10, 70, 10);
        events.register(PlayerRegionEnterEvent.class, e -> e.setCancelled(true));
        CorePlayer player = player();

        assertFalse(regions.updateMembership(player, 5, 65, 5));
        assertEquals(0, player.getRegionMembership().inside().length);
    }

    @Test
    void cancellingTheLeaveEventKeepsThemInside() {
        regions.create("arena", 0, 60, 0, 10, 70, 10);
        CorePlayer player = player();
        assertTrue(regions.updateMembership(player, 5, 65, 5));
        events.register(PlayerRegionLeaveEvent.class, e -> e.setCancelled(true));

        assertFalse(regions.updateMembership(player, 50, 65, 5), "the round isn't over");
        assertEquals(1, player.getRegionMembership().inside().length, "still a member");
    }

    @Test
    void aRefusedCrossingLeavesMembershipExactlyAsItWas() {
        regions.create("outer", 0, 0, 0, 100, 100, 100);
        CoreRegion wall = regions.create("wall", 40, 40, 40, 60, 60, 60);
        wall.deny(RegionFlag.ENTRY);
        CorePlayer player = player();
        assertTrue(regions.updateMembership(player, 10, 10, 10));
        assertEquals(1, player.getRegionMembership().inside().length);

        assertFalse(regions.updateMembership(player, 50, 50, 50), "the walled region refuses");

        assertEquals(1, player.getRegionMembership().inside().length,
                "and the step is undone whole — not half-applied into the outer region twice");
        assertEquals("outer", player.getRegionMembership().inside()[0].getName());
    }

    // ===== The file =====

    @Test
    void regionsRoundTripThroughTheFileWithTheirFlags(@TempDir Path dir) throws Exception {
        regions.create("spawn", -20, 60, -20, 20, 90, 20).deny(RegionFlag.BUILD);
        regions.get("spawn").deny(RegionFlag.PVP);
        regions.create("arena", 100, 60, 100, 140, 80, 140).deny(RegionFlag.ENTRY);
        Path file = dir.resolve("regions.jdb");
        regions.save(file);

        RegionManager reloaded = new RegionManager(new EventBus());
        reloaded.load(file);

        assertEquals(2, reloaded.size());
        Region spawn = reloaded.get("spawn");
        assertEquals(-20, spawn.getMinX());
        assertEquals(90, spawn.getMaxY());
        assertFalse(spawn.allows(RegionFlag.BUILD));
        assertFalse(spawn.allows(RegionFlag.PVP));
        assertTrue(spawn.allows(RegionFlag.INTERACT), "a flag nobody denied stays allowed");
        assertFalse(reloaded.get("arena").allows(RegionFlag.ENTRY));
    }

    @Test
    void anUntouchedSetIsNotRewritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("regions.jdb");
        regions.create("a", 0, 0, 0, 1, 1, 1);
        assertTrue(regions.isDirty());
        regions.save(file);
        assertFalse(regions.isDirty(), "saving settles it");

        regions.saveIfDirty(file); // must not touch the file
        assertFalse(regions.isDirty());

        regions.markDirty(); // what a flag edit does
        assertTrue(regions.isDirty());
    }

    @Test
    void loadingRestoresEnforcementSoRulesAreLiveBeforeTheFirstLogin(@TempDir Path dir) throws Exception {
        regions.create("keep", 0, 60, 0, 10, 70, 10).deny(RegionFlag.BUILD);
        Path file = dir.resolve("regions.jdb");
        regions.save(file);

        EventBus freshBus = new EventBus();
        RegionManager reloaded = new RegionManager(freshBus);
        assertFalse(freshBus.hasListeners(BlockBreakEvent.class));
        reloaded.load(file);

        assertTrue(freshBus.hasListeners(BlockBreakEvent.class),
                "a boot-loaded region protects itself with no script involved");
        assertTrue(freshBus.post(new BlockBreakEvent(player(), 5, 65, 5, 1)).isCancelled());
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) throws Exception {
        regions.load(dir.resolve("nothing-here.jdb"));

        assertEquals(0, regions.size());
    }

    /** The bare minimum a CorePlayer needs to exist in these tests. */
    private static final class Conn implements PlayerConnection {
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public void sendMessage(String message) { }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
