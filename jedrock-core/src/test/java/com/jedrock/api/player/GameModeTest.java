package com.jedrock.api.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link GameMode} parsing / display used by config and the /gamemode command. */
class GameModeTest {

    @Test
    void parsesNamesShorthandsAndIds() {
        assertEquals(GameMode.SURVIVAL, GameMode.fromString("survival"));
        assertEquals(GameMode.SURVIVAL, GameMode.fromString("S"));
        assertEquals(GameMode.SURVIVAL, GameMode.fromString("0"));
        assertEquals(GameMode.CREATIVE, GameMode.fromString("creative"));
        assertEquals(GameMode.CREATIVE, GameMode.fromString("c"));
        assertEquals(GameMode.CREATIVE, GameMode.fromString("1"));
        assertEquals(GameMode.ADVENTURE, GameMode.fromString("a"));
        assertEquals(GameMode.SPECTATOR, GameMode.fromString("sp"));
    }

    @Test
    void trimsAndIgnoresCase() {
        assertEquals(GameMode.CREATIVE, GameMode.fromString("  Creative "));
    }

    @Test
    void unknownIsNull() {
        assertNull(GameMode.fromString("god"));
        assertNull(GameMode.fromString(""));
        assertNull(GameMode.fromString(null));
    }

    @Test
    void flightAndDisplay() {
        assertTrue(GameMode.CREATIVE.allowsFlight());
        assertTrue(GameMode.SPECTATOR.allowsFlight());
        assertFalse(GameMode.SURVIVAL.allowsFlight());
        assertFalse(GameMode.ADVENTURE.allowsFlight());
        assertEquals("Survival", GameMode.SURVIVAL.displayName());
        assertEquals("Creative", GameMode.CREATIVE.displayName());
    }
}
