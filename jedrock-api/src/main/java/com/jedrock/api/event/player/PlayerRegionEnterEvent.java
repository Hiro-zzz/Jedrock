package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.region.Region;

/**
 * A player stepped into a {@linkplain Region region} they were not in a moment ago — the trigger most
 * scripted content hangs off, and the reason regions are worth having at all.
 *
 * <p>Fired once per crossing, not per movement packet: the core keeps what a player was inside and only
 * posts this when that set actually changes. A player who walks into three overlapping regions at once
 * gets one event per region.
 *
 * <p><b>Cancelling refuses the entry</b> — the player is snapped back to where they stood before the step,
 * exactly as a denied {@link com.jedrock.api.region.RegionFlag#ENTRY} does, and they are not recorded as
 * having been inside. A joining or teleporting player who materialises inside a region also gets this
 * event, but there is nowhere to snap them back to, so cancelling it there only suppresses the membership.
 */
public class PlayerRegionEnterEvent extends CancellablePlayerEvent {

    private final Region region;

    public PlayerRegionEnterEvent(Player player, Region region) {
        super(player);
        this.region = region;
    }

    /** The region being entered. */
    public Region getRegion() {
        return region;
    }
}
