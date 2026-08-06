package com.jedrock.network.pe;

import com.jedrock.api.item.ItemNames;
import com.jedrock.api.world.Blocks;
import com.jedrock.network.pe.v014.Pe014Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard between the two halves of "what an item is": the creative palettes say what a client can
 * <em>draw</em>, {@link ItemNames} says what a person can <em>type</em>. They are different jobs and live
 * in different modules — this test is the only place both are visible, so it is where they are kept in
 * step. Add a state to a palette without naming it and this fails, which is the point.
 */
class PaletteNamingTest {

    /**
     * States a palette offers that the name table deliberately leaves unnamed, and why. Each one is a
     * place where the two legacy numberings disagree, so any single name would be wrong on half the
     * server; they stay reachable as {@code id:meta}.
     */
    private static final Set<Integer> UNNAMED = new LinkedHashSet<>();

    static {
        UNNAMED.add(Blocks.state(158, 0));   // a dropper on Java, a wooden slab on Bedrock
        for (int meta = 0; meta <= 5; meta++) {
            UNNAMED.add(Blocks.state(158, meta));  // …and the Bedrock wooden-slab metas with it
            if (meta >= 1) {
                UNNAMED.add(Blocks.state(85, meta));   // fence wood types: a meta on Bedrock, an id on Java
            }
        }
        UNNAMED.add(Blocks.state(243, 0));   // podzol on Bedrock; 3:2 already means podzol
        UNNAMED.add(Blocks.state(145, 8));   // the anvil variant PMMP ships; damage state, not a kind
        UNNAMED.add(Blocks.state(351, 8));   // the one dye PMMP ships; dyes are a colour family of their own
        UNNAMED.add(Blocks.state(325, 10));  // water bucket — named, but as 325:10 it is a bucket meta
    }

    @Test
    @DisplayName("every state either edition offers in creative has a name")
    void palettesAreNamed() {
        StringJoiner missing = new StringJoiner(", ");
        int checked = 0;
        for (int state : allPaletteStates()) {
            checked++;
            if (ItemNames.isNamed(state) || UNNAMED.contains(state)) {
                continue;
            }
            missing.add(Blocks.idOf(state) + ":" + Blocks.metaOf(state));
        }
        assertTrue(checked > 300, "the palettes should be the whole creative menu, not a sample");
        assertTrue(missing.length() == 0,
                "these palette states have no name (add one to ItemNames, or list it as deliberately "
                        + "unnamed here with a reason): " + missing);
    }

    private static Set<Integer> allPaletteStates() {
        Set<Integer> states = new LinkedHashSet<>();
        for (int state : PeCreativePalette.forV115()) {
            states.add(state);
        }
        for (int state : Pe014Blocks.creativePalette()) {
            states.add(state);
        }
        return states;
    }
}
