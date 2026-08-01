package com.jedrock.core.item;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom items: the key a stack carries, the definition a registry gives that key, and the behaviours
 * dispatched from the events the core already routes.
 */
class ItemRegistryTest {

    private static final int SWORD = 276 << 4;
    private static final int STONE = 1 << 4;

    private final EventBus events = new EventBus();
    private final ItemRegistry registry = new ItemRegistry(events);
    private final CoreWorld world = new CoreWorld("items", Dimension.OVERWORLD, 1L);

    private CorePlayer player() {
        return new CorePlayer(UUID.randomUUID(), "P", new Conn(), world, world.getSpawnLocation(),
                GameMode.SURVIVAL);
    }

    // ===== The registry =====

    @Test
    void anItemIsDefinedAndReadBackByKey() {
        CoreCustomItem blade = registry.define("frostblade", SWORD, "{aqua}Frostblade",
                new String[]{"{gray}Cold."});

        assertEquals("frostblade", blade.getKey());
        assertEquals(SWORD, blade.getState());
        assertEquals("{aqua}Frostblade", blade.getDisplayName());
        assertEquals(1, blade.getLore().length);
        assertNotNull(registry.get("FROSTBLADE"), "keys are matched case-insensitively");
    }

    @Test
    void aKeyThatWouldNotSurviveAFileIsRefused() {
        assertNull(registry.define("", SWORD, null, null));
        assertNull(registry.define("  ", SWORD, null, null));
        assertNull(registry.define("frost blade", SWORD, null, null), "a space");
        assertNull(registry.define("frost/blade", SWORD, null, null), "a separator");
        assertNotNull(registry.define("frost.blade-2_x", SWORD, null, null));
    }

    @Test
    void redefiningReplacesTheDefinitionAndEveryStackFollows() {
        registry.define("frostblade", SWORD, "{aqua}Frostblade", null);
        Container chest = new Container(27);
        chest.set(0, SWORD, 1, "frostblade");

        registry.define("frostblade", SWORD, "{red}Emberblade", null); // a hot reload

        assertEquals("frostblade", chest.customKeyAt(0), "the stack was never touched");
        assertEquals("{red}Emberblade", registry.displayNameOf(chest.customKeyAt(0)),
                "but it reads as the new definition — the stack carries the key, not a copy");
    }

    @Test
    void aStackWhoseKeyNothingDefinesIsSimplyVanilla() {
        Container chest = new Container(27);
        chest.set(0, SWORD, 1, "frostblade"); // the plugin was uninstalled

        assertNull(registry.get("frostblade"));
        assertNull(registry.displayNameOf("frostblade"), "no name to show — the vanilla one is honest");
        assertEquals(SWORD, chest.stateAt(0), "and it is still a perfectly good sword");
    }

    // ===== The stack =====

    @Test
    void aCustomStackNeverMergesWithAnOrdinaryOne() {
        Container inv = new Container(36);
        inv.give(SWORD, 0, 36);                 // an ordinary sword
        inv.give(SWORD, 0, 36, "frostblade");   // and a named one

        assertEquals(1, inv.countAt(0));
        assertNull(inv.customKeyAt(0));
        assertEquals(1, inv.countAt(1), "it took its own slot");
        assertEquals("frostblade", inv.customKeyAt(1));
    }

    @Test
    void twoOfTheSameCustomItemDoStack() {
        Container inv = new Container(36);
        inv.give(SWORD, 0, 36, "frostblade");
        inv.give(SWORD, 0, 36, "frostblade");

        assertEquals(2, inv.countAt(0));
        assertEquals("frostblade", inv.customKeyAt(0));
    }

    @Test
    void takingMatchesTheKeyAndClearingForgetsIt() {
        Container inv = new Container(36);
        inv.set(0, SWORD, 1, "frostblade");
        inv.set(1, SWORD, 1);

        assertEquals(1, inv.take(SWORD, 0, 36), "the ordinary one is not the custom one");
        assertEquals("frostblade", inv.customKeyAt(0), "the custom stack is untouched");

        assertEquals(0, inv.take(SWORD, 0, 36, "frostblade"));
        assertTrue(inv.isEmpty(0));
        assertNull(inv.customKeyAt(0), "an emptied slot forgets what it held");
    }

    // ===== Behaviour =====

    @Test
    void aUseHookRunsAndCanConsumeTheAction() {
        List<String> log = new ArrayList<>();
        CoreCustomItem blade = registry.define("frostblade", SWORD, null, null);
        blade.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> {
            log.add("used by " + p.getName());
            return true;
        });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "frostblade");

        assertTrue(events.post(new PlayerUseItemEvent(holder, true)).isCancelled(),
                "returning true consumes the use, which is what cancelling means");
        assertEquals(List.of("used by P"), log);
    }

    @Test
    void aHookOnlyFiresForTheItemThatCarriesIt() {
        List<String> log = new ArrayList<>();
        registry.define("frostblade", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> { log.add("fired"); return true; });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1); // the same sword, but ordinary

        assertFalse(events.post(new PlayerUseItemEvent(holder, true)).isCancelled());
        assertEquals(List.of(), log, "an ordinary sword is not the custom one");
    }

    @Test
    void aBreakHookSeesTheBlockAndCanKeepIt() {
        int[] seen = {-1, -1, -1, -1};
        registry.define("wand", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.BREAK, (p, ctx) -> {
                    seen[0] = ctx.x(); seen[1] = ctx.y(); seen[2] = ctx.z(); seen[3] = ctx.blockState();
                    return true;
                });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "wand");

        assertTrue(events.post(new BlockBreakEvent(holder, 4, 70, -2, STONE)).isCancelled(),
                "the item handled it, so the block stays");
        assertEquals(List.of(4, 70, -2, STONE), List.of(seen[0], seen[1], seen[2], seen[3]));
    }

    @Test
    void aHitHookSeesTheVictimAndCanSuppressTheDamage() {
        String[] hit = {null};
        registry.define("frostblade", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.HIT, (p, ctx) -> {
                    hit[0] = ctx.target().getName();
                    return true;
                });
        registry.hooksChanged();
        CorePlayer attacker = player();
        attacker.getInventory().set(0, SWORD, 1, "frostblade");
        CorePlayer victim = player();

        assertTrue(registry.onHit(attacker, victim), "the item answered for the hit");
        assertEquals("P", hit[0]);
        assertFalse(registry.onHit(victim, attacker), "the empty-handed one has nothing to say");
    }

    @Test
    void aHoldHookReadsTheSlotBeingSwitchedToNotTheHand() {
        List<String> log = new ArrayList<>();
        registry.define("cursed", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.HOLD, (p, ctx) -> { log.add("held"); return true; });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(3, SWORD, 1, "cursed"); // in slot 3; the hand is still slot 0

        assertTrue(events.post(new PlayerHeldItemChangeEvent(holder, 0, 3, 0, SWORD)).isCancelled(),
                "refusing the switch, read from the slot being switched TO");
        assertEquals(List.of("held"), log);
    }

    @Test
    void aThrowingHookDoesNotConsumeTheAction() {
        registry.define("buggy", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> { throw new IllegalStateException("boom"); });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "buggy");

        assertFalse(events.post(new PlayerUseItemEvent(holder, true)).isCancelled(),
                "a script's mistake must not swallow the player's action");
    }

    @Test
    void dispatchExistsOnlyWhileSomeItemHasABehaviour() {
        assertFalse(events.hasListeners(BlockBreakEvent.class),
                "a purely cosmetic item must not make every block edit build an event");

        registry.define("plain", SWORD, "{gold}Shiny", null);
        assertFalse(events.hasListeners(BlockBreakEvent.class), "a name is not a behaviour");

        CoreCustomItem wand = registry.define("wand", SWORD, null, null);
        wand.setHook(CoreCustomItem.Trigger.BREAK, (p, ctx) -> false);
        registry.hooksChanged();
        assertTrue(events.hasListeners(BlockBreakEvent.class));

        wand.setHook(CoreCustomItem.Trigger.BREAK, null);
        registry.hooksChanged();
        assertFalse(events.hasListeners(BlockBreakEvent.class), "and gone with the last behaviour");
    }

    // ===== Cooldown =====

    @Test
    void aCoolingItemAnswersOnceAndThenStopsAnswering() {
        List<String> log = new ArrayList<>();
        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(60_000L);
        bomb.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> { log.add("boom"); return true; });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "bomb");

        assertTrue(events.post(new PlayerUseItemEvent(holder, true)).isCancelled());
        assertFalse(events.post(new PlayerUseItemEvent(holder, true)).isCancelled(),
                "still warm, and with no cooldown hook the click falls through as the vanilla item");
        assertEquals(List.of("boom"), log, "the behaviour ran exactly once");
    }

    @Test
    void aCooldownHookRunsInsteadAndCanConsumeTheAction() {
        double[] remaining = {-1};
        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(60_000L);
        bomb.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> true);
        bomb.setHook(CoreCustomItem.Trigger.COOLDOWN, (p, ctx) -> {
            remaining[0] = ctx.remainingMillis();
            return true;
        });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "bomb");

        events.post(new PlayerUseItemEvent(holder, true));
        assertTrue(events.post(new PlayerUseItemEvent(holder, true)).isCancelled(),
                "the cooldown hook returned true, so it swallowed the click");
        assertTrue(remaining[0] > 0 && remaining[0] <= 60_000L, "it was told how long is left");
    }

    @Test
    void aCoolingBreakStillKnowsWhichBlockItRefused() {
        int[] seen = {-1, -1, -1};
        CoreCustomItem wand = registry.define("wand", SWORD, null, null);
        wand.setCooldownMillis(60_000L);
        wand.setHook(CoreCustomItem.Trigger.BREAK, (p, ctx) -> true);
        wand.setHook(CoreCustomItem.Trigger.COOLDOWN, (p, ctx) -> {
            seen[0] = ctx.x(); seen[1] = ctx.y(); seen[2] = ctx.z();
            return false;
        });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "wand");

        events.post(new BlockBreakEvent(holder, 1, 2, 3, STONE));
        assertFalse(events.post(new BlockBreakEvent(holder, 4, 70, -2, STONE)).isCancelled(),
                "the hook returned false, so the block breaks the ordinary way");
        assertEquals(List.of(4, 70, -2), List.of(seen[0], seen[1], seen[2]));
    }

    @Test
    void clearingACooldownMakesTheItemAnswerAgain() {
        List<String> log = new ArrayList<>();
        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(60_000L);
        bomb.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> { log.add("boom"); return true; });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "bomb");

        events.post(new PlayerUseItemEvent(holder, true));
        assertTrue(registry.cooldownRemaining(holder, "bomb") > 0);
        registry.clearCooldown(holder, "bomb");

        assertEquals(0L, registry.cooldownRemaining(holder, "bomb"));
        assertTrue(events.post(new PlayerUseItemEvent(holder, true)).isCancelled());
        assertEquals(List.of("boom", "boom"), log);
    }

    @Test
    void takingAnItemInHandIsNotSomethingACooldownRefuses() {
        List<String> log = new ArrayList<>();
        CoreCustomItem cursed = registry.define("cursed", SWORD, null, null);
        cursed.setCooldownMillis(60_000L);
        cursed.setHook(CoreCustomItem.Trigger.HOLD, (p, ctx) -> { log.add("held"); return false; });
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(3, SWORD, 1, "cursed");

        events.post(new PlayerHeldItemChangeEvent(holder, 0, 3, 0, SWORD));
        events.post(new PlayerHeldItemChangeEvent(holder, 0, 3, 0, SWORD));

        assertEquals(List.of("held", "held"), log, "a hotbar switch is not an act the item gets to refuse");
    }

    @Test
    void aCooldownSurvivesTheHotReloadThatReplacesItsDefinition() {
        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(60_000L);
        bomb.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> true);
        registry.hooksChanged();
        CorePlayer holder = player();
        holder.getInventory().set(0, SWORD, 1, "bomb");
        events.post(new PlayerUseItemEvent(holder, true));

        CoreCustomItem reloaded = registry.define("bomb", SWORD, null, null); // saving the script
        reloaded.setCooldownMillis(60_000L);
        reloaded.setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> true);
        registry.hooksChanged();

        assertTrue(registry.cooldownRemaining(holder, "bomb") > 0,
                "editing a plugin must not hand every player a fresh bomb");
    }

    @Test
    void theQuitCleanupExistsOnlyWhileSomethingCools() {
        registry.define("plain", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.USE, (p, ctx) -> true);
        registry.hooksChanged();
        assertFalse(events.hasListeners(com.jedrock.api.event.player.PlayerQuitEvent.class),
                "a server whose items never cool pays nothing for cooldowns");

        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(1_000L);
        registry.hooksChanged();
        assertTrue(events.hasListeners(com.jedrock.api.event.player.PlayerQuitEvent.class));

        registry.remove("bomb");
        assertFalse(events.hasListeners(com.jedrock.api.event.player.PlayerQuitEvent.class),
                "and gone with the last cooling item");
    }

    @Test
    void aCooldownAloneIsNotABehaviourWorthDispatching() {
        CoreCustomItem bomb = registry.define("bomb", SWORD, null, null);
        bomb.setCooldownMillis(1_000L);
        bomb.setHook(CoreCustomItem.Trigger.COOLDOWN, (p, ctx) -> true);
        registry.hooksChanged();

        assertFalse(events.hasListeners(BlockBreakEvent.class),
                "a cooldown hook only ever fires instead of something else — alone it has nothing to say");
    }

    @Test
    void removingADefinitionTearsDownItsDispatch() {
        registry.define("wand", SWORD, null, null)
                .setHook(CoreCustomItem.Trigger.BREAK, (p, ctx) -> false);
        registry.hooksChanged();

        assertTrue(registry.remove("wand"));

        assertFalse(events.hasListeners(BlockBreakEvent.class));
        assertEquals(0, registry.size());
    }

    /** The bare minimum a CorePlayer needs here. */
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
