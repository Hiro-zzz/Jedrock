package com.jedrock.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-authoritative click semantics: pick up, place, merge, swap, half-pick and quick-move. */
class InventoryClickTest {

    private static final int STONE = 1 << 4;      // id 1, meta 0
    private static final int DIRT = 3 << 4;       // id 3, meta 0

    @Test
    void leftClickPicksUpWholeStackThenPlacesIt() {
        Container c = new Container(5);
        Cursor cur = new Cursor();
        c.set(0, STONE, 10);

        InventoryClick.normal(c, cur, 0, false);   // pick up all
        assertTrue(c.isEmpty(0));
        assertEquals(STONE, cur.state());
        assertEquals(10, cur.count());

        InventoryClick.normal(c, cur, 1, false);   // place all into empty slot 1
        assertTrue(cur.isEmpty());
        assertEquals(STONE, c.stateAt(1));
        assertEquals(10, c.countAt(1));
    }

    @Test
    void leftClickMergesSameItemUpToMaxStack() {
        Container c = new Container(5);
        Cursor cur = new Cursor();
        c.set(0, STONE, 60);
        cur.set(STONE, 20);

        InventoryClick.normal(c, cur, 0, false);   // 60 + 4 = 64, 16 stay on cursor
        assertEquals(64, c.countAt(0));
        assertEquals(16, cur.count());
    }

    @Test
    void leftClickSwapsDifferentItems() {
        Container c = new Container(5);
        Cursor cur = new Cursor();
        c.set(0, STONE, 5);
        cur.set(DIRT, 3);

        InventoryClick.normal(c, cur, 0, false);
        assertEquals(DIRT, c.stateAt(0));
        assertEquals(3, c.countAt(0));
        assertEquals(STONE, cur.state());
        assertEquals(5, cur.count());
    }

    @Test
    void rightClickPicksUpHalfThenPlacesOne() {
        Container c = new Container(5);
        Cursor cur = new Cursor();
        c.set(0, STONE, 7);

        InventoryClick.normal(c, cur, 0, true);    // half of 7 = 4 to cursor, 3 stay
        assertEquals(4, cur.count());
        assertEquals(3, c.countAt(0));

        InventoryClick.normal(c, cur, 1, true);    // place one into empty slot 1
        assertEquals(1, c.countAt(1));
        assertEquals(3, cur.count());
    }

    @Test
    void shiftClickQuickMovesIntoTheOtherRegion() {
        Container c = new Container(10);
        c.set(0, STONE, 40);                        // "hotbar" slot 0

        InventoryClick.shift(c, 0, 1, 10);          // move into region [1,10)
        assertTrue(c.isEmpty(0), "source emptied");
        assertEquals(STONE, c.stateAt(1));
        assertEquals(40, c.countAt(1));
    }

    @Test
    void shiftToMovesAcrossContainersAndKeepsTheOverflow() {
        Container chest = new Container(27);
        Container player = new Container(41);
        chest.set(0, STONE, 40);
        // A player with a nearly-full storage: one stone slot at 63 leaves room for 1 before it caps.
        player.set(0, STONE, 63);                    // hotbar slot 0 (a storage slot)

        InventoryClick.shiftTo(chest, 0, player, 0, 36); // chest slot 0 → player storage
        assertEquals(64, player.countAt(0), "stacked onto the existing stone up to 64");
        // 39 remain to place; with 35 empty storage slots that all fit, so the chest slot empties.
        assertTrue(chest.isEmpty(0), "the rest spilled into empty player slots");
    }
}
