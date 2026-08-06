package com.jedrock.network;

import com.jedrock.api.item.Enchantment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing about enchantments that cannot be got right by intuition: the two editions number them
 * differently, and three of them are outright reordered rather than merely offset.
 *
 * <p>These are the tests that would have caught the mistake worth catching — a table copied from one
 * source and used for both wires. That failure is silent on every client: nothing errors, somebody just
 * quietly gets thorns instead of respiration.
 */
class EnchantmentIdsTest {

    @Test
    @DisplayName("Java's ids are the ones minecraft-data lists for 1.12")
    void javaTable() {
        assertEquals(0, EnchantmentIds.javaId(Enchantment.PROTECTION));
        assertEquals(5, EnchantmentIds.javaId(Enchantment.RESPIRATION));
        assertEquals(6, EnchantmentIds.javaId(Enchantment.AQUA_AFFINITY));
        assertEquals(7, EnchantmentIds.javaId(Enchantment.THORNS));
        assertEquals(16, EnchantmentIds.javaId(Enchantment.SHARPNESS));
        assertEquals(32, EnchantmentIds.javaId(Enchantment.EFFICIENCY));
        assertEquals(34, EnchantmentIds.javaId(Enchantment.UNBREAKING));
        assertEquals(48, EnchantmentIds.javaId(Enchantment.POWER));
        assertEquals(62, EnchantmentIds.javaId(Enchantment.LURE));
    }

    @Test
    @DisplayName("Bedrock's are PocketMine's, and they are one contiguous run")
    void bedrockTable() {
        assertEquals(0, EnchantmentIds.bedrockId(Enchantment.PROTECTION));
        assertEquals(5, EnchantmentIds.bedrockId(Enchantment.THORNS));
        assertEquals(6, EnchantmentIds.bedrockId(Enchantment.RESPIRATION));
        assertEquals(8, EnchantmentIds.bedrockId(Enchantment.AQUA_AFFINITY));
        assertEquals(9, EnchantmentIds.bedrockId(Enchantment.SHARPNESS));
        assertEquals(15, EnchantmentIds.bedrockId(Enchantment.EFFICIENCY));
        assertEquals(17, EnchantmentIds.bedrockId(Enchantment.UNBREAKING));
        assertEquals(19, EnchantmentIds.bedrockId(Enchantment.POWER));
        assertEquals(24, EnchantmentIds.bedrockId(Enchantment.LURE));
    }

    @Test
    @DisplayName("the three that are reordered, not merely offset")
    void theSilentTrap() {
        // Sharpness is the obvious gap, and the one anybody would notice.
        assertNotEquals(EnchantmentIds.javaId(Enchantment.SHARPNESS),
                EnchantmentIds.bedrockId(Enchantment.SHARPNESS));
        // These three are the dangerous ones: both editions use all three numbers, for different things.
        assertEquals(5, EnchantmentIds.javaId(Enchantment.RESPIRATION));
        assertEquals(5, EnchantmentIds.bedrockId(Enchantment.THORNS));
        assertEquals(7, EnchantmentIds.javaId(Enchantment.THORNS));
        assertEquals(7, EnchantmentIds.bedrockId(Enchantment.DEPTH_STRIDER));
        // So sending Java's number on a Bedrock wire would be a working packet meaning something else.
        assertNotEquals(EnchantmentIds.javaId(Enchantment.THORNS),
                EnchantmentIds.bedrockId(Enchantment.THORNS));
    }

    @Test
    @DisplayName("neither table has a collision or a hole")
    void tablesAreWellFormed() {
        Set<Integer> java = new HashSet<>();
        Set<Integer> bedrock = new HashSet<>();
        for (Enchantment e : Enchantment.values()) {
            assertTrue(java.add(EnchantmentIds.javaId(e)), e + " collides on the Java table");
            assertTrue(bedrock.add(EnchantmentIds.bedrockId(e)), e + " collides on the Bedrock table");
        }
        // Bedrock's run is dense from 0, which is why 0.14's "knows up to 24" is a workable gate.
        for (int id = 0; id <= EnchantmentIds.MAX_BEDROCK_014_ID; id++) {
            assertTrue(bedrock.contains(id), "Bedrock id " + id + " should be covered");
        }
    }

    @Test
    @DisplayName("every canonical enchantment is one 0.14 knows")
    void the014Gate() {
        // The canonical set is deliberately the pre-1.9 one, which is exactly 0.14's own table — so the
        // gate never fires today. It is here for the day somebody adds mending.
        for (Enchantment e : Enchantment.values()) {
            assertTrue(EnchantmentIds.supportedBy014(e), e + " should be renderable on 0.14");
        }
    }
}
