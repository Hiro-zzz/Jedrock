package com.jedrock.core.effect;

import com.jedrock.api.entity.Effect;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerEffectEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.BlindJudge;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.player.PlayerTracker;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server keeps about an effect, and the two places it actually acts on one.
 *
 * <p>Most of an effect is the client's business — the swirl, the tint, the speed — so most of what there
 * is to test here is that the server holds the right little and lets go of it at the right moment.
 */
class EffectServiceTest {

    private final CoreWorld world = new CoreWorld("fx", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final PlayerTracker tracker = new PlayerTracker(players, 4);
    private final EffectService effects = new EffectService(players, tracker, events);

    private CorePlayer join(String name, Conn conn) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), name, conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    @Test
    @DisplayName("an effect is held, told to the client once, and readable back")
    void applyingOne() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);

        assertTrue(effects.apply(player, Effect.SPEED, 1, 30, true));

        assertEquals(List.of("speed:1:600"), conn.sent, "one packet, and nothing per tick after it");
        assertEquals(1, effects.amplifierOf(player, Effect.SPEED));
        assertTrue(effects.has(player, Effect.SPEED));
        assertFalse(effects.has(player, Effect.STRENGTH));
        assertEquals(1, effects.active(player).size());
    }

    @Test
    @DisplayName("a listener can refuse one, or rescale it")
    void theEventDecides() {
        events.register(PlayerEffectEvent.class, e -> {
            if (e.getEffect() == Effect.STRENGTH) {
                e.setCancelled(true);
            } else {
                e.setDurationSeconds(e.getDurationSeconds() / 2);
            }
        });
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);

        assertFalse(effects.apply(player, Effect.STRENGTH, 0, 30, true), "refused");
        assertFalse(effects.has(player, Effect.STRENGTH));
        assertTrue(effects.apply(player, Effect.SPEED, 0, 60, true));
        assertEquals(List.of("speed:0:600"), conn.sent, "halved before it was sent, not after");
    }

    @Test
    @DisplayName("removing one tells the client and forgets it")
    void removingOne() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        effects.apply(player, Effect.SPEED, 0, 30, true);

        assertTrue(effects.remove(player, Effect.SPEED));
        assertFalse(effects.remove(player, Effect.SPEED), "…and only the first time");
        assertEquals(List.of("-speed"), conn.removed);
        assertTrue(effects.active(player).isEmpty());
    }

    @Test
    @DisplayName("one that has run out is gone when read, and retired on the next pass")
    void expiry() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        effects.apply(player, Effect.SPEED, 0, 0, true);   // already over

        assertTrue(effects.active(player).isEmpty(), "a reader never sees an expired effect");
        assertFalse(effects.has(player, Effect.SPEED));

        effects.expiryTick(20);
        assertEquals(List.of("-speed"), conn.removed, "and the client is told, once");
        assertTrue(player.getEffects().isEmpty(), "the table is emptied, not left growing");
    }

    @Test
    @DisplayName("the expiry pass is gated, and skips players holding nothing")
    void expiryIsCheap() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        effects.apply(player, Effect.SPEED, 0, 0, true);

        effects.expiryTick(7);      // not on the interval
        assertTrue(conn.removed.isEmpty(), "nothing happens off the interval");
        effects.expiryTick(40);
        assertEquals(1, conn.removed.size());
    }

    @Test
    @DisplayName("speed widens what the judge will believe — and nothing else does")
    void moveAllowance() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        assertEquals(1.0, effects.moveAllowance(player), 1e-9, "an ordinary player gets the ordinary limit");

        effects.apply(player, Effect.SPEED, 1, 30, true);          // Speed II
        double allowance = effects.moveAllowance(player);
        assertTrue(allowance > 1.0, "a sped-up player may cover more ground");

        // The judge is what this is for: a step that a fast player could plausibly take.
        BlindJudge judge = new BlindJudge(true, 6.0, 8.0);
        assertFalse(judge.allowsMove(0, 64, 0, 10, 64, 0), "10 blocks is a jump, ordinarily");
        assertTrue(judge.allowsMove(0, 64, 0, 10, 64, 0, allowance), "…but not for somebody under speed II");
        assertFalse(judge.allowsMove(0, 64, 0, 100, 64, 0, allowance), "crossing the map still isn't");

        effects.apply(player, Effect.NIGHT_VISION, 0, 30, true);
        assertEquals(allowance, effects.moveAllowance(player), 1e-9,
                "an effect the client merely draws changes nothing here");
    }

    @Test
    @DisplayName("strength adds to a hit and weakness takes away, on the attacker's side")
    void outgoingDamage() {
        Conn conn = new Conn();
        CorePlayer attacker = join("A", conn);
        assertEquals(2, effects.scaleOutgoing(attacker, 2), "no effects, no change");

        effects.apply(attacker, Effect.STRENGTH, 0, 30, true);
        assertEquals(5, effects.scaleOutgoing(attacker, 2), "vanilla adds 3 a level");

        effects.remove(attacker, Effect.STRENGTH);
        effects.apply(attacker, Effect.WEAKNESS, 0, 30, true);
        assertEquals(0, effects.scaleOutgoing(attacker, 2), "…and weakness can take a bare hand to nothing");
    }

    @Test
    @DisplayName("invisibility takes the avatar away, and gives it back")
    void invisibility() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);

        effects.apply(player, Effect.INVISIBILITY, 0, 30, true);
        assertTrue(player.isInvisible(), "there is no avatar to see, on any edition");

        effects.remove(player, Effect.INVISIBILITY);
        assertFalse(player.isInvisible());
    }

    @Test
    @DisplayName("re-stating what is held sends what is LEFT, not what was asked for")
    void resend() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        effects.apply(player, Effect.SPEED, 0, 30, true);
        conn.sent.clear();

        effects.resend(player);

        assertEquals(1, conn.sent.size());
        String line = conn.sent.get(0);
        int ticks = Integer.parseInt(line.substring(line.lastIndexOf(':') + 1));
        assertTrue(ticks > 0 && ticks <= 600, "the remaining duration, not the original: " + ticks);
    }

    @Test
    @DisplayName("clearing takes off everything and reports how much there was")
    void clearAll() {
        Conn conn = new Conn();
        CorePlayer player = join("A", conn);
        effects.apply(player, Effect.SPEED, 0, 30, true);
        effects.apply(player, Effect.NIGHT_VISION, 0, 30, true);

        assertEquals(2, effects.clear(player));
        assertEquals(0, effects.clear(player));
        assertTrue(effects.active(player).isEmpty());
    }

    /** Records what the client was told, in the order it was told. */
    private static final class Conn implements PlayerConnection {
        final List<String> sent = new ArrayList<>();
        final List<String> removed = new ArrayList<>();

        @Override public void sendEffect(Effect effect, int amplifier, int durationTicks, boolean particles) {
            sent.add(effect.getKey() + ":" + amplifier + ":" + durationTicks);
        }

        @Override public void removeEffect(Effect effect) {
            removed.add("-" + effect.getKey());
        }

        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public void sendMessage(String message) { }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z,
                                         float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
