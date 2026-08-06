package com.jedrock.api.item;

import com.jedrock.api.world.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The name table: what it accepts, what it gives back, and what it refuses. */
class ItemNamesTest {

    @Test
    @DisplayName("a name resolves to its state")
    void namesResolve() {
        assertEquals(Blocks.state(35, 14), ItemNames.parse("red_wool"));
        assertEquals(Blocks.state(1, 0), ItemNames.parse("stone"));
        assertEquals(Blocks.state(276, 0), ItemNames.parse("diamond_sword"));
    }

    @Test
    @DisplayName("case and surrounding space don't matter")
    void lenientAboutTyping() {
        assertEquals(Blocks.state(35, 14), ItemNames.parse("  Red_Wool "));
    }

    @Test
    @DisplayName("a family name takes an explicit meta")
    void familyWithMeta() {
        assertEquals(Blocks.state(35, 14), ItemNames.parse("wool:14"));
        assertEquals(Blocks.state(5, 3), ItemNames.parse("planks:3"));
    }

    @Test
    @DisplayName("a bare number is an id, not a packed state")
    void bareNumberIsAnId() {
        // What a person means by 276 is a diamond sword, the way every legacy server has read it —
        // not the packed state 276, which would be a jungle log.
        assertEquals(Blocks.state(276, 0), ItemNames.parse("276"));
        assertEquals(Blocks.state(35, 0), ItemNames.parse("35"));
    }

    @Test
    @DisplayName("id:meta is always accepted, named or not")
    void numericPair() {
        assertEquals(Blocks.state(35, 14), ItemNames.parse("35:14"));
        // 158 is deliberately unnamed (a dropper on Java, a wooden slab on Bedrock) and still reachable.
        assertEquals(Blocks.state(158, 0), ItemNames.parse("158"));
    }

    @Test
    @DisplayName("nonsense is refused rather than guessed at")
    void refusesNonsense() {
        assertEquals(-1, ItemNames.parse("frostblade"));  // a custom item's key is not a state
        assertEquals(-1, ItemNames.parse("wool:99"));     // a meta is four bits
        assertEquals(-1, ItemNames.parse("-3"));
        assertEquals(-1, ItemNames.parse(""));
        assertEquals(-1, ItemNames.parse(null));
    }

    @Test
    @DisplayName("every name round-trips through the state it names")
    void everyNameRoundTrips() {
        for (String name : ItemNames.names()) {
            int state = ItemNames.parse(name);
            assertTrue(state > 0, name + " should resolve");
            // The name we get back may be the canonical one rather than this alias, but it must name
            // the same state — otherwise the table contradicts itself.
            assertEquals(state, ItemNames.parse(ItemNames.name(state)),
                    name + " should round-trip through its canonical name");
        }
    }

    @Test
    @DisplayName("an unnamed state prints as id or id:meta, never null")
    void unnamedStatesStillPrint() {
        assertEquals("158", ItemNames.name(Blocks.state(158, 0)));
        assertEquals("158:3", ItemNames.name(Blocks.state(158, 3)));
        assertFalse(ItemNames.isNamed(Blocks.state(158, 0)));
    }

    @Test
    @DisplayName("a colour family names all sixteen, distinctly")
    void colourFamiliesAreComplete() {
        for (int meta = 0; meta < 16; meta++) {
            int wool = Blocks.state(35, meta);
            assertTrue(ItemNames.isNamed(wool), "wool meta " + meta + " should be named");
            assertEquals(wool, ItemNames.parse(ItemNames.name(wool)));
        }
        assertNotEquals(ItemNames.name(Blocks.state(35, 0)), ItemNames.name(Blocks.state(35, 14)));
    }

    @Test
    @DisplayName("no two names claim the same spelling")
    void noDuplicateNames() {
        List<String> names = ItemNames.names();
        assertEquals(names.size(), names.stream().distinct().count(), "a name is spelled once");
        assertTrue(ItemNames.size() > 250, "the table should cover the palettes, not a handful");
    }
}
