package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * A player swung their arm — the nearest thing this server has to "left-clicked".
 *
 * <p>Every edition reports it and every edition has always relayed it, because the swing is an animation
 * other players need to see. What it was never surfaced as is an <em>input</em>, which left scripts with
 * only the right-click half of the mouse: {@link PlayerUseItemEvent} for use, {@link PlayerDamageEvent}
 * and {@link PlayerInteractEntityEvent} for what a left click <em>hit</em>, and nothing at all for a left
 * click that hit nothing. A wand you wave is the obvious thing that needs it.
 *
 * <p>Read it as an animation, not as an intent. The client swings for its own reasons — mining sends one
 * per tick while the block is being dug, and some clients swing on a miss — so this fires often and says
 * only "an arm moved". Anything more specific (which block, which player) has its own event, fires
 * alongside this one, and is the one to prefer.
 *
 * <p>Cancelling suppresses the <em>relay</em>: other players don't see the swing. It cannot suppress the
 * swing on the swinger's own screen, which their client drew before the packet was sent, and it does not
 * stop whatever else the click turns out to have done.
 */
public final class PlayerSwingArmEvent extends CancellablePlayerEvent {

    public PlayerSwingArmEvent(Player player) {
        super(player);
    }
}
