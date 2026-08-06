package com.jedrock.core.item;

import com.jedrock.api.item.Enchantment;
import com.jedrock.api.item.Enchantments;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.inventory.Cursor;
import com.jedrock.core.inventory.InventoryClick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an enchantment is to a <em>stack</em>: part of its identity, so it survives every move and keeps
 * two stacks apart that would otherwise merge.
 *
 * <p>The merge case is the one worth pinning. Everything else about enchantments is drawn by the client;
 * this is the part where getting it wrong quietly destroys somebody's sword by dissolving it into a pile
 * of ordinary ones.
 */
class EnchantedStackTest {

    private static final int SWORD = 276 << 4;

    @Test
    @DisplayName("an enchanted stack does not merge with a plain one drawn the same way")
    void enchantmentsKeepStacksApart() {
        Container c = new Container(9);
        c.set(0, SWORD, 1, null, null, Enchantments.of(Enchantment.SHARPNESS, 3));

        // Giving a plain sword must not stack onto the sharpness one — they are different items, however
        // identically they are drawn.
        int slot = c.give(SWORD, 0, 9, null, null, Enchantments.NONE);

        assertEquals(1, slot, "the plain one takes its own slot");
        assertEquals(1, c.countAt(0));
        assertEquals(3, c.enchantmentsAt(0).level(Enchantment.SHARPNESS));
        assertTrue(c.enchantmentsAt(1).isEmpty());
    }

    @Test
    @DisplayName("two identically enchanted stacks do merge")
    void sameEnchantmentsStillStack() {
        Container c = new Container(9);
        Enchantments sharp = Enchantments.of(Enchantment.SHARPNESS, 3);
        c.set(0, SWORD, 1, null, null, sharp);

        int slot = c.give(SWORD, 0, 9, null, null, Enchantments.of(Enchantment.SHARPNESS, 3));

        assertEquals(0, slot, "value equality, not identity — a fresh set of the same thing merges");
        assertEquals(2, c.countAt(0));
    }

    @Test
    @DisplayName("picking a stack up and putting it down keeps its enchantments")
    void aMoveDoesNotStripThem() {
        Container c = new Container(9);
        Cursor cursor = new Cursor();
        c.set(0, SWORD, 1, null, null, Enchantments.of(Enchantment.UNBREAKING, 2));

        InventoryClick.normal(c, cursor, 0, false);   // pick up
        assertEquals(2, cursor.enchantments().level(Enchantment.UNBREAKING), "carried on the cursor");
        InventoryClick.normal(c, cursor, 4, false);   // put down somewhere else

        assertTrue(c.isEmpty(0));
        assertEquals(2, c.enchantmentsAt(4).level(Enchantment.UNBREAKING), "and put back down intact");
        assertTrue(cursor.isEmpty());
    }

    @Test
    @DisplayName("a shift-click into another container carries them too")
    void quickMoveCarriesThem() {
        Container from = new Container(9);
        Container to = new Container(9);
        from.set(0, SWORD, 2, null, null, Enchantments.of(Enchantment.FORTUNE, 1));

        InventoryClick.shiftTo(from, 0, to, 0, 9);

        assertTrue(from.isEmpty(0));
        assertEquals(2, to.countAt(0));
        assertEquals(1, to.enchantmentsAt(0).level(Enchantment.FORTUNE));
    }

    @Test
    @DisplayName("clearing a slot forgets them, and an empty slot cannot be enchanted")
    void enchantmentsBelongToAStack() {
        Container c = new Container(9);
        c.setEnchantments(0, Enchantments.of(Enchantment.SHARPNESS, 1));
        assertTrue(c.enchantmentsAt(0).isEmpty(), "there is no stack here to enchant");

        c.set(0, SWORD, 1);
        c.setEnchantments(0, Enchantments.of(Enchantment.SHARPNESS, 1));
        assertFalse(c.enchantmentsAt(0).isEmpty());

        c.clear(0);
        assertTrue(c.enchantmentsAt(0).isEmpty(), "and they go when the stack goes");
    }

    @Test
    @DisplayName("the compact form round-trips, which is what the level file stores")
    void compactForm() {
        Enchantments set = Enchantments.of(Enchantment.SHARPNESS, 3)
                .with(Enchantment.UNBREAKING, 1);

        String text = set.toCompactString();
        assertEquals(set, Enchantments.parse(text));
        assertEquals(Enchantments.NONE, Enchantments.parse(""));
        assertEquals(Enchantments.NONE, Enchantments.parse(null));
        // A name from a build that knew more than this one: skipped, not fatal — the rest of the stack
        // is worth more than the one enchantment nothing here can name.
        assertEquals(Enchantments.of(Enchantment.SHARPNESS, 2),
                Enchantments.parse("sharpness:2,mending:1"));
    }

    @Test
    @DisplayName("a level of zero takes it off, as it does everywhere else here")
    void zeroRemoves() {
        Enchantments set = Enchantments.of(Enchantment.SHARPNESS, 3);
        assertTrue(set.with(Enchantment.SHARPNESS, 0).isEmpty());
        assertEquals(Enchantments.NONE, set.without(Enchantment.SHARPNESS));
    }
}
