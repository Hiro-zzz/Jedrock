package com.jedrock.api.event.player;

import com.jedrock.api.event.Event;
import com.jedrock.api.player.Player;

/**
 * Base for every event caused by, or concerning, one player. Carries the {@link Player} so subclasses
 * don't each re-declare it. Not cancellable in itself — a subclass adds that only where vetoing makes
 * sense (see {@link CancellablePlayerEvent}).
 */
public abstract class PlayerEvent implements Event {

    private final Player player;

    protected PlayerEvent(Player player) {
        this.player = player;
    }

    /** The player this event is about. */
    public Player getPlayer() {
        return player;
    }
}
