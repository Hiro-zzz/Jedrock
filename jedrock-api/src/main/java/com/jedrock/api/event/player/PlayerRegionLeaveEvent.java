package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.region.Region;

/**
 * A player stepped out of a {@linkplain Region region} they were inside — the other half of
 * {@link PlayerRegionEnterEvent}, and where scripted content usually cleans up after itself.
 *
 * <p>Fired once per crossing, and once per region when a step leaves several at the same time. A player
 * who disconnects inside a region does <em>not</em> get one: they didn't leave, they stopped existing, and
 * a script that cares about that already has {@code PlayerQuit}.
 *
 * <p><b>Cancelling refuses the exit</b> — the player is snapped back inside and stays a member. That is
 * how an arena keeps someone in until a round is over. When the exit is a teleport rather than a step the
 * snap-back can't apply, so cancelling only keeps the membership.
 */
public class PlayerRegionLeaveEvent extends CancellablePlayerEvent {

    private final Region region;

    public PlayerRegionLeaveEvent(Player player, Region region) {
        super(player);
        this.region = region;
    }

    /** The region being left. */
    public Region getRegion() {
        return region;
    }
}
