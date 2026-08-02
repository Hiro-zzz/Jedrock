package com.jedrock.core.entity;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a puppet is <em>looking</em>, as against which way it is turned.
 *
 * <p>The two were one number for as long as puppets existed, so a guard could not watch you cross a room
 * without shuffling round to follow. These pin the rule that separates them — and, just as importantly,
 * the cases where they must still move together, because a head left behind by its own body is the way
 * this goes wrong rather than merely staying still.
 */
class PuppetGlanceTest {

    private final CoreWorld world = new CoreWorld("glance", Dimension.OVERWORLD, 1L);
    private final EntityDirector entities = new EntityDirector(new PlayerRegistry(), world);

    private PuppetEntity puppetFacing(float yaw) {
        return entities.spawnPuppet(EntityType.ZOMBIE, new Location(world, 0, 64, 0, yaw, 0f), "Guard");
    }

    @Test
    void aFreshPuppetLooksWhereItStands() {
        assertEquals(45f, puppetFacing(45f).getHeadYaw(), 1e-4,
                "nothing has aimed it, so the head is wherever the body faces");
    }

    @Test
    void aGlanceTurnsTheHeadAndLeavesTheBody() {
        PuppetEntity guard = puppetFacing(0f);

        guard.setHeadYaw(90f);

        assertEquals(90f, guard.getHeadYaw(), 1e-4);
        assertEquals(0f, guard.getLocation().yaw(), 1e-4, "the body has not moved — that is the point");
    }

    @Test
    void glanceAtAimsTheHeadAtTheTargetOnly() {
        PuppetEntity guard = puppetFacing(0f);   // 0 = facing +Z (south)

        guard.glanceAt(new Location(world, -10, 64, 0, 0f, 0f)); // due -X (west), which is yaw +90

        assertEquals(90f, guard.getHeadYaw(), 1e-3);
        assertEquals(0f, guard.getLocation().yaw(), 1e-4, "still facing its post");
    }

    @Test
    void aGlanceStillCarriesPitchBecauseThereIsNowhereElseToPutIt() {
        PuppetEntity guard = puppetFacing(0f);

        guard.glanceAt(new Location(world, 0, 80, 10, 0f, 0f)); // up and away

        assertNotEquals(0f, guard.getLocation().pitch(), "looking up is a pose, not a head yaw");
    }

    @Test
    void turningOnTheSpotTurnsTheHeadToo() {
        PuppetEntity guard = puppetFacing(0f);
        guard.setHeadYaw(90f);

        guard.setRotation(180f, 0f);

        assertEquals(180f, guard.getHeadYaw(), 1e-4,
                "being turned round is not a glance — the head comes with the body");
    }

    @Test
    void lookAtTurnsBoth() {
        PuppetEntity guard = puppetFacing(0f);

        guard.lookAt(new Location(world, -10, 64, 0, 0f, 0f));

        assertEquals(guard.getLocation().yaw(), guard.getHeadYaw(), 1e-4);
    }

    @Test
    void beingCarriedSomewhereFacingANewWayBringsTheHeadAlong() {
        PuppetEntity guard = puppetFacing(0f);
        guard.setHeadYaw(90f);

        guard.teleport(new Location(world, 5, 64, 5, 180f, 0f));

        assertEquals(180f, guard.getHeadYaw(), 1e-4);
    }

    @Test
    void beingMovedWithoutTurningLeavesAnAimedHeadAimed() {
        PuppetEntity guard = puppetFacing(0f);
        guard.setHeadYaw(90f);

        guard.teleport(new Location(world, 5, 64, 5, 0f, 0f)); // same facing, new spot

        assertEquals(90f, guard.getHeadYaw(), 1e-4,
                "a guard that walks its beat while watching you keeps watching you");
    }
}
