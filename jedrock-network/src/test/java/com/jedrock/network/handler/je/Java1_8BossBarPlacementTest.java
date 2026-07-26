package com.jedrock.network.handler.je;

import org.junit.jupiter.api.Test;

import static com.jedrock.network.handler.je.Java1_8ProtocolHandler.bossAheadX;
import static com.jedrock.network.handler.je.Java1_8ProtocolHandler.bossAheadY;
import static com.jedrock.network.handler.je.Java1_8ProtocolHandler.bossAheadZ;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 1.8 boss bar is an invisible wither, and the 1.8 client only draws the bar for a wither it is
 * <b>rendering</b> — so the whole trick is keeping that wither in front of the camera. These pin the
 * placement (ViaRewind's: 48 blocks straight down the line of sight), because getting it wrong is
 * invisible in every other way — the packets still send, the client still spawns the entity, and no bar
 * appears. A wither parked under the player's feet is exactly what silently failed before.
 */
class Java1_8BossBarPlacementTest {

    private static final double D = 48.0;   // BOSS_BAR_DISTANCE
    private static final double EPS = 1e-6;

    @Test
    void facingSouthPutsItSouth() {
        // 1.8 yaw 0 = facing +Z (south).
        assertEquals(10.0, bossAheadX(10, 0f, 0f), EPS, "no sideways drift");
        assertEquals(64.0, bossAheadY(64, 0f), EPS, "level look keeps it at eye height");
        assertEquals(20.0 + D, bossAheadZ(20, 0f, 0f), EPS, "48 blocks ahead, not behind");
    }

    @Test
    void turningTurnsItWithYou() {
        // yaw 90 = facing -X (west): the wither swings a quarter turn with the player.
        assertEquals(10.0 - D, bossAheadX(10, 90f, 0f), EPS);
        assertEquals(20.0, bossAheadZ(20, 90f, 0f), EPS);

        // yaw 180 = facing -Z (north).
        assertEquals(10.0, bossAheadX(10, 180f, 0f), EPS);
        assertEquals(20.0 - D, bossAheadZ(20, 180f, 0f), EPS);
    }

    @Test
    void lookingUpAndDownCarriesItVertically() {
        // Pitch is negative looking up in Minecraft, so straight up lifts the wither by the full distance.
        assertEquals(64.0 + D, bossAheadY(64, -90f), EPS, "straight up");
        assertEquals(64.0 - D, bossAheadY(64, 90f), EPS, "straight down");
        // …and a full vertical look leaves nothing for the horizontal offset.
        assertEquals(10.0, bossAheadX(10, 45f, -90f), EPS, "no horizontal reach when looking straight up");
        assertEquals(20.0, bossAheadZ(20, 45f, -90f), EPS);
    }

    @Test
    void theOffsetIsAlwaysTheSameDistanceAway() {
        // Whatever the facing, the wither sits on a sphere of exactly BOSS_BAR_DISTANCE around the player:
        // near enough to be rendered, far enough not to be walked into.
        for (float yaw = -180; yaw <= 180; yaw += 37f) {
            for (float pitch = -90; pitch <= 90; pitch += 23f) {
                double dx = bossAheadX(0, yaw, pitch);
                double dy = bossAheadY(0, pitch);
                double dz = bossAheadZ(0, yaw, pitch);
                assertEquals(D, Math.sqrt(dx * dx + dy * dy + dz * dz), 1e-9,
                        "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }
}
