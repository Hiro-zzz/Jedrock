package com.jedrock.core.moderation;

import com.jedrock.api.event.EventBus;
import com.jedrock.core.data.DataStore;
import com.jedrock.core.permission.OpList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may be here and who may speak.
 *
 * <p>The parts worth pinning are the ones that fail quietly if they are wrong: an expired ban that keeps
 * refusing somebody, a reason containing the delimiter that eats the rest of the record, an ip ban that
 * matches the port and therefore matches nothing, and the order the gate asks its questions in.
 */
class ModerationTest {

    /** A store in memory, so a test can restart the server without a filesystem. */
    private static final class MemoryStore implements DataStore {
        private final Map<String, Map<String, String>> tables = new ConcurrentHashMap<>();

        @Override public String describe() { return "memory"; }

        @Override public Map<String, String> load(String table) {
            return new LinkedHashMap<>(tables.getOrDefault(table, Map.of()));
        }

        @Override public void save(String table, Map<String, String> rows) {
            tables.put(table, new LinkedHashMap<>(rows));
        }

        @Override public void close() { }
    }

    private final MemoryStore store = new MemoryStore();

    private Punishment ban(String target, String reason, long expiresAt) {
        return new Punishment(Punishment.Kind.BAN, target, reason, "Tester",
                System.currentTimeMillis(), expiresAt);
    }

    // ===== The store =====

    @Test
    void aBanIsFoundByAnyCasingOfTheName() {
        PunishmentStore punishments = new PunishmentStore(store);
        punishments.add(ban("Griefer", "spawn", 0));

        long now = System.currentTimeMillis();
        assertNotNull(punishments.find(Punishment.Kind.BAN, "griefer", now));
        assertNotNull(punishments.find(Punishment.Kind.BAN, "GRIEFER", now));
        assertNull(punishments.find(Punishment.Kind.BAN, "somebodyelse", now));
    }

    @Test
    void anExpiredOneReadsAsAbsentWithoutAnythingSweepingIt() {
        PunishmentStore punishments = new PunishmentStore(store);
        long now = System.currentTimeMillis();
        punishments.add(ban("griefer", "spam", now + 1000));

        assertNotNull(punishments.find(Punishment.Kind.BAN, "griefer", now));
        assertNull(punishments.find(Punishment.Kind.BAN, "griefer", now + 1001),
                "no timer ran; it simply stopped counting");
        assertEquals(0, punishments.count(Punishment.Kind.BAN, now + 1001));
    }

    @Test
    void aReasonMayContainTheThingsThatCouldHaveBrokenTheFile() {
        PunishmentStore punishments = new PunishmentStore(store);
        String awkward = "said a|b and x=y {red}too";
        punishments.add(ban("griefer", awkward, 0));

        // Reload from the same rows, exactly as a restart would.
        PunishmentStore reloaded = new PunishmentStore(store);
        Punishment found = reloaded.find(Punishment.Kind.BAN, "griefer", System.currentTimeMillis());

        assertNotNull(found);
        assertEquals(awkward, found.reason(), "the reason is last and the split is limited");
        assertEquals("Tester", found.issuer());
    }

    @Test
    void aNewlineInAReasonCannotEndTheLine() {
        Punishment p = ban("griefer", "one\ntwo", 0);
        assertFalse(p.reason().contains("\n"), "a newline would take the rest of the file with it");
    }

    @Test
    void everythingSurvivesARestart() {
        PunishmentStore first = new PunishmentStore(store);
        first.add(ban("griefer", "spawn", 0));
        first.add(new Punishment(Punishment.Kind.MUTE, "loud", "caps", "Tester",
                System.currentTimeMillis(), 0));

        PunishmentStore second = new PunishmentStore(store);
        long now = System.currentTimeMillis();

        assertNotNull(second.find(Punishment.Kind.BAN, "griefer", now));
        assertNotNull(second.find(Punishment.Kind.MUTE, "loud", now));
        assertNull(second.find(Punishment.Kind.MUTE, "griefer", now), "a ban is not a mute");
    }

    @Test
    void anExpiredEntryIsDroppedOnTheNextWriteRatherThanOnATimer() {
        PunishmentStore punishments = new PunishmentStore(store);
        punishments.add(ban("gone", "brief", System.currentTimeMillis() - 1)); // already lapsed
        punishments.add(ban("stays", "permanent", 0));                          // …and this writes the table

        assertFalse(store.load("bans").containsKey("gone"), "swept at the only moment it is free");
        assertTrue(store.load("bans").containsKey("stays"));
    }

    @Test
    void pardonLiftsEveryKindAtOnce() {
        PunishmentStore punishments = new PunishmentStore(store);
        punishments.add(ban("griefer", "spawn", 0));
        punishments.add(new Punishment(Punishment.Kind.MUTE, "griefer", "caps", "Tester", 0, 0));

        assertEquals(2, punishments.pardon("griefer"));
        assertEquals(0, punishments.pardon("griefer"), "and there is nothing left to lift");
    }

    // ===== The gate =====

    private Moderation moderation(@TempDir Path dir) {
        return new Moderation(store, new EventBus(), new OpList(dir.resolve("ops.txt")));
    }

    @Test
    void aBannedNameIsRefusedAndSaysWhy(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getPunishments().add(ban("griefer", "Broke spawn", 0));

        String refusal = moderation.refusalFor("griefer", "1.2.3.4:5000");

        assertNotNull(refusal);
        assertTrue(refusal.contains("Broke spawn"), "being told why is the point of a reason");
        assertNull(moderation.refusalFor("somebodyelse", "1.2.3.4:5000"));
    }

    @Test
    void anIpBanMatchesTheAddressWithoutItsPort(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getPunishments().add(new Punishment(Punishment.Kind.IP_BAN, "1.2.3.4",
                "evading", "Tester", 0, 0));

        // The port is a different number on every reconnect, so a ban on the whole string bans nothing.
        assertNotNull(moderation.refusalFor("anyone", "/1.2.3.4:51234"));
        assertNotNull(moderation.refusalFor("anyone", "1.2.3.4:9999"));
        assertNull(moderation.refusalFor("anyone", "5.6.7.8:51234"));
    }

    @Test
    void hostOfLeavesAnIpv6AddressAlone() {
        assertEquals("1.2.3.4", Moderation.hostOf("/1.2.3.4:25565"));
        assertEquals("[::1]", Moderation.hostOf("[::1]:25565"));
        assertEquals("", Moderation.hostOf(null));
    }

    @Test
    void theWhitelistRefusesEverybodyElseButNeverAnOperator(@TempDir Path dir) {
        OpList ops = new OpList(dir.resolve("ops.txt"));
        ops.add("admin");
        Moderation moderation = new Moderation(store, new EventBus(), ops);
        moderation.getWhitelist().add("alice");
        moderation.getWhitelist().setEnabled(true);

        assertNull(moderation.refusalFor("alice", "1.2.3.4:1"));
        assertNull(moderation.refusalFor("admin", "1.2.3.4:1"),
                "an operator locked out of their own server by their own command is a foot-gun");
        assertNotNull(moderation.refusalFor("bob", "1.2.3.4:1"));
    }

    @Test
    void aWhitelistThatIsOffDoesNothing(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getWhitelist().add("alice");

        assertNull(moderation.refusalFor("bob", "1.2.3.4:1"),
                "which is what makes it safe to build the list before turning it on");
    }

    @Test
    void aBanBeatsBeingWhitelisted(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getWhitelist().add("alice");
        moderation.getWhitelist().setEnabled(true);
        moderation.getPunishments().add(ban("alice", "still banned", 0));

        assertNotNull(moderation.refusalFor("alice", "1.2.3.4:1"));
    }

    @Test
    void anOperatorIsStillBanned(@TempDir Path dir) {
        OpList ops = new OpList(dir.resolve("ops.txt"));
        ops.add("admin");
        Moderation moderation = new Moderation(store, new EventBus(), ops);
        moderation.getPunishments().add(ban("admin", "a decision somebody made", 0));

        assertNotNull(moderation.refusalFor("admin", "1.2.3.4:1"),
                "the whitelist is waived for an op; a ban is not — the console can always lift it");
    }

    @Test
    void theLoginGateIsWiredToTheEvent(@TempDir Path dir) {
        EventBus events = new EventBus();
        Moderation moderation = new Moderation(store, events, new OpList(dir.resolve("ops.txt")));
        moderation.getPunishments().add(ban("griefer", "spawn", 0));

        var event = events.post(new com.jedrock.api.event.player.PlayerLoginEvent(
                java.util.UUID.randomUUID(), "griefer", "1.2.3.4:1"));

        assertTrue(event.isCancelled(), "a ban IS a cancelled login — no new gate was needed");
        assertNotNull(event.getKickReason());
    }

    @Test
    void aMuteIsFoundForTheChatPath(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getPunishments().add(new Punishment(Punishment.Kind.MUTE, "loud", "caps",
                "Tester", System.currentTimeMillis(), 0));

        assertNotNull(moderation.muteFor("loud"));
        assertNull(moderation.muteFor("quiet"));
        assertTrue(moderation.muteNotice(moderation.muteFor("loud")).contains("caps"));
    }

    @Test
    void silenceRefusesAMutedSpeakerAndTellsThemWhy(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getPunishments().add(new Punishment(Punishment.Kind.MUTE, "loud", "caps",
                "Tester", System.currentTimeMillis(), 0));
        java.util.List<String> said = new java.util.ArrayList<>();

        assertTrue(moderation.silence(player("loud", said)),
                "/me, /msg and /say all ask this before they speak");
        assertEquals(1, said.size(), "and the speaker is told, rather than talking into a void");
        assertFalse(moderation.silence(player("quiet", said)));
    }

    @Test
    void theConsoleIsNeverMuted(@TempDir Path dir) {
        Moderation moderation = moderation(dir);
        moderation.getPunishments().add(new Punishment(Punishment.Kind.MUTE, "Server", "…", "Tester", 0, 0));

        // A CommandSender that is not a Player — there is nobody to punish.
        assertFalse(moderation.silence(new com.jedrock.api.command.CommandSender() {
            @Override public String getName() { return "Server"; }
            @Override public void sendMessage(String message) { }
            @Override public boolean hasPermission(String node) { return true; }
            @Override public boolean isOp() { return true; }
        }));
    }

    /**
     * A real {@code CorePlayer} over a connection that only remembers what it was told — building the
     * whole {@code Player} surface by hand here would be a second implementation to keep in step.
     */
    private static com.jedrock.core.player.CorePlayer player(String name, java.util.List<String> said) {
        com.jedrock.core.world.CoreWorld world =
                new com.jedrock.core.world.CoreWorld("mod", com.jedrock.api.world.Dimension.OVERWORLD, 1L);
        return new com.jedrock.core.player.CorePlayer(java.util.UUID.randomUUID(), name,
                new com.jedrock.api.player.PlayerConnection() {
                    @Override public com.jedrock.api.protocol.ProtocolVersion getProtocolVersion() {
                        return com.jedrock.api.protocol.ProtocolVersion.JE_1_12_2;
                    }
                    @Override public void sendMessage(String message) { said.add(message); }
                    @Override public String getAddress() { return "1.2.3.4:1"; }
                    @Override public void sendPacket(Object packet) { }
                    @Override public void addToTab(java.util.UUID uuid, String n) { }
                    @Override public void removeFromTab(java.util.UUID uuid) { }
                    @Override public void showPlayer(java.util.UUID uuid, String n, long id,
                                                     double x, double y, double z, float yaw, float pitch) { }
                    @Override public void hidePlayer(java.util.UUID uuid, long id) { }
                    @Override public void moveAvatar(long id, double x, double y, double z,
                                                     float yaw, float pitch) { }
                    @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
                    @Override public void sendBlockChange(int x, int y, int z, int state) { }
                    @Override public void setGameMode(com.jedrock.api.player.GameMode m) { }
                    @Override public void swingArm(long entityId) { }
                    @Override public void setPose(long id, boolean a, boolean b, boolean c) { }
                    @Override public void close(String reason) { }
                    @Override public boolean isActive() { return true; }
                },
                world, world.getSpawnLocation(), com.jedrock.api.player.GameMode.SURVIVAL);
    }

    // ===== Whitelist and last-seen persistence =====

    @Test
    void theWhitelistAndItsSwitchBothSurviveARestart() {
        Whitelist first = new Whitelist(store);
        first.add("Alice");
        first.setEnabled(true);

        Whitelist second = new Whitelist(store);

        assertTrue(second.isEnabled(), "enabling it and restarting must not quietly turn it off again");
        assertTrue(second.contains("alice"));
        assertEquals(1, second.size());
    }

    @Test
    void lastSeenRemembersWhenSomebodyLeft() {
        LastSeen first = new LastSeen(store);
        first.record("Alice", 1_700_000_000_000L);

        LastSeen second = new LastSeen(store);

        assertEquals(1_700_000_000_000L, second.lastSeen("alice"));
        assertEquals(0L, second.lastSeen("nobody"), "never seen is 0, not a guess");
    }

    @Test
    void anUnreadableRowCostsOneEntryAndNotTheFile() {
        Map<String, String> rows = new HashMap<>();
        rows.put("good", "Tester|0|0|fine");
        rows.put("bad", "this is not a record");
        store.save("bans", rows);

        PunishmentStore punishments = new PunishmentStore(store);

        assertNotNull(punishments.find(Punishment.Kind.BAN, "good", System.currentTimeMillis()));
        assertNull(punishments.find(Punishment.Kind.BAN, "bad", System.currentTimeMillis()));
    }
}
