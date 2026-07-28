package com.jedrock.network.handler.je;

import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The same-dimension bounce. A Java client rebuilds its world only when the dimension it is told
 * <em>changes</em>, so travelling between two worlds of the same kind sends one throwaway Respawn to a
 * dimension the player is not in, then the real one. This pins the only thing that has to be true of
 * that throwaway: it is never the dimension being entered.
 */
class JavaDimensionBounceTest {

    @Test
    void theBounceIsNeverTheDimensionBeingEntered() {
        for (Dimension to : Dimension.values()) {
            assertNotEquals(to.getId(), JavaEditionProtocolHandler.bounceDimension(to),
                    "bouncing into " + to + " would leave the client holding the old terrain");
        }
    }
}
