package com.jedrock.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a stack <em>is</em> has to survive being moved.
 *
 * <p>A custom item's key and its own per-stack data used to live only where a script had put them: every
 * click, shift and transfer rebuilt the stack out of a state and a count, so picking a named sword up and
 * putting it down again turned it into the ordinary sword it is merely drawn as. These pin the pipeline
 * that no longer does that.
 */
class CustomStackIdentityTest {

    private static final int SWORD = 276 << 4;
    private static final int STONE = 1 << 4;

    // ===== The cursor: where every window move passes through =====

    @Test
    void pickingUpAndPuttingDownKeepsTheItemItWas() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, SWORD, 1, "frostblade", "{\"charges\":2}");

        InventoryClick.normal(c, cur, 0, false);   // pick up
        assertEquals("frostblade", cur.customKey());
        assertEquals("{\"charges\":2}", cur.customData());

        InventoryClick.normal(c, cur, 4, false);   // …and down somewhere else
        assertEquals("frostblade", c.customKeyAt(4));
        assertEquals("{\"charges\":2}", c.customDataAt(4));
        assertNull(c.customKeyAt(0), "and the slot it left forgot it");
    }

    @Test
    void splittingAStackGivesBothHalvesTheSameIdentity() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, SWORD, 4, "frostblade", "sharp");

        InventoryClick.normal(c, cur, 0, true);    // right click: half up

        assertEquals(2, cur.count());
        assertEquals(2, c.countAt(0));
        assertEquals("frostblade", cur.customKey());
        assertEquals("frostblade", c.customKeyAt(0), "the half left behind is the same sword");
        assertEquals("sharp", c.customDataAt(0));
    }

    @Test
    void aSwapCarriesEachStackToWhereTheOtherWas() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, STONE, 5, "runestone", null);
        cur.set(SWORD, 1, "frostblade", "sharp");

        InventoryClick.normal(c, cur, 0, false);

        assertEquals("frostblade", c.customKeyAt(0));
        assertEquals("sharp", c.customDataAt(0));
        assertEquals("runestone", cur.customKey());
        assertNull(cur.customData());
    }

    @Test
    void aNamedStackDoesNotMergeIntoAnOrdinaryOneOfTheSameItem() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, SWORD, 1);                        // ordinary
        cur.set(SWORD, 1, "frostblade", null);     // named

        InventoryClick.normal(c, cur, 0, false);

        assertEquals("frostblade", c.customKeyAt(0), "it swapped rather than merged");
        assertNull(cur.customKey());
        assertEquals(1, c.countAt(0), "…which is what a player sees: two different swords");
    }

    @Test
    void twoStacksOfTheSameItemMergeOnlyWhileNothingHasHappenedToOne() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, SWORD, 1, "wand", "{\"charges\":1}");
        cur.set(SWORD, 1, "wand", "{\"charges\":3}");

        InventoryClick.normal(c, cur, 0, false);

        assertEquals(1, c.countAt(0), "a spent wand must not dissolve into a full one");
        assertEquals("{\"charges\":3}", c.customDataAt(0), "they swapped");
        assertEquals("{\"charges\":1}", cur.customData());
    }

    @Test
    void mergingTwoIdenticalStacksLeavesTheIdentityAlone() {
        Container c = new Container(9);
        Cursor cur = new Cursor();
        c.set(0, SWORD, 1, "wand", "charged");
        cur.set(SWORD, 2, "wand", "charged");

        InventoryClick.normal(c, cur, 0, false);

        assertEquals(3, c.countAt(0));
        assertEquals("wand", c.customKeyAt(0));
        assertEquals("charged", c.customDataAt(0));
        assertTrue(cur.isEmpty());
    }

    // ===== Quick-move =====

    @Test
    void aShiftClickCarriesIdentityIntoTheOtherRegion() {
        Container inv = new Container(36);
        inv.set(0, SWORD, 1, "frostblade", "sharp");

        InventoryClick.shift(inv, 0, 9, 36);       // hotbar → main

        assertTrue(inv.isEmpty(0));
        assertEquals("frostblade", inv.customKeyAt(9));
        assertEquals("sharp", inv.customDataAt(9));
    }

    @Test
    void aQuickMoveIntoAFullContainerLeavesTheRemainderAsItself() {
        Container src = new Container(9);
        Container dst = new Container(1);
        src.set(0, SWORD, 3, "frostblade", "sharp");
        dst.set(0, STONE, 1);                      // the only slot, and it is taken

        InventoryClick.shiftTo(src, 0, dst, 0, 1);

        assertEquals(3, src.countAt(0), "nothing fit");
        assertEquals("frostblade", src.customKeyAt(0), "and what stayed is still what it was");
        assertEquals("sharp", src.customDataAt(0));
    }

    // ===== setCount: the call that used to be spelled set(slot, sameState, fewer) =====

    @Test
    void spendingFromAStackDoesNotRebuildIt() {
        Container c = new Container(9);
        c.set(0, SWORD, 5, "frostblade", "sharp");

        c.setCount(0, 4);

        assertEquals("frostblade", c.customKeyAt(0));
        assertEquals("sharp", c.customDataAt(0));
    }

    @Test
    void countingAStackDownToNothingEmptiesTheSlot() {
        Container c = new Container(9);
        c.set(0, SWORD, 1, "frostblade", "sharp");

        c.setCount(0, 0);

        assertTrue(c.isEmpty(0));
        assertNull(c.customKeyAt(0));
        assertNull(c.customDataAt(0), "an emptied slot forgets everything, not just the item");
    }

    @Test
    void puttingAnOrdinaryItemInASlotClearsWhatWasThere() {
        Container c = new Container(9);
        c.set(0, SWORD, 1, "frostblade", "sharp");

        c.set(0, STONE, 1);

        assertNull(c.customKeyAt(0), "a frostblade's name must not end up on a stack of stone");
        assertNull(c.customDataAt(0));
    }

    @Test
    void dataBelongsToAStackSoAnEmptySlotRefusesIt() {
        Container c = new Container(9);

        c.setCustomData(0, "charges:3");

        assertNull(c.customDataAt(0));
    }

    // ===== The trail: a move the client made and only told us about afterwards =====

    @Test
    void aDisplacedStackIsClaimedByTheReportThatPutsItDown() {
        CustomStackTrail trail = new CustomStackTrail(1_000_000_000L); // a 1s window
        long now = 1_000L;

        trail.displaced(SWORD, "frostblade", "sharp", now);
        CustomStackTrail.Displaced claimed = trail.claim(SWORD, now + 1_000L);

        assertNotNull(claimed);
        assertEquals("frostblade", claimed.customKey());
        assertEquals("sharp", claimed.customData());
    }

    @Test
    void oneDisplacementExplainsOnlyOneArrival() {
        CustomStackTrail trail = new CustomStackTrail(1_000_000_000L);
        trail.displaced(SWORD, "frostblade", null, 0L);

        assertNotNull(trail.claim(SWORD, 1L));
        assertNull(trail.claim(SWORD, 2L), "the second sword is a different stack");
    }

    @Test
    void adifferentItemDoesNotClaimIt() {
        CustomStackTrail trail = new CustomStackTrail(1_000_000_000L);
        trail.displaced(SWORD, "frostblade", null, 0L);

        assertNull(trail.claim(STONE, 1L));
    }

    @Test
    void aTrailExpires() {
        CustomStackTrail trail = new CustomStackTrail(1_000_000L); // 1ms
        trail.displaced(SWORD, "frostblade", null, 0L);

        assertNull(trail.claim(SWORD, 2_000_000L), "past the window the client is simply believed");
    }

    @Test
    void anOrdinaryStackLeavesNoTrailToConfuseTheNextReport() {
        CustomStackTrail trail = new CustomStackTrail(1_000_000_000L);
        trail.displaced(SWORD, null, null, 0L);

        assertNull(trail.claim(SWORD, 1L), "there was nothing worth carrying");
    }

    @Test
    void aWindowSetToZeroTurnsTheRescueOff() {
        CustomStackTrail trail = new CustomStackTrail(0L);
        trail.displaced(SWORD, "frostblade", null, 0L);

        assertNull(trail.claim(SWORD, 1L));
    }
}
