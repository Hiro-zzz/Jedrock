package com.jedrock.api.event.player;

import com.jedrock.api.event.Cancellable;
import com.jedrock.api.player.Player;

/**
 * A {@link PlayerEvent} that a listener can veto. Cancelling means "the core must not do the thing this
 * event announced" — suppress the chat line, leave the block alone, refuse the move. What cancellation
 * concretely undoes is documented on each subclass and enforced by the core that posts it.
 */
public abstract class CancellablePlayerEvent extends PlayerEvent implements Cancellable {

    private boolean cancelled;

    protected CancellablePlayerEvent(Player player) {
        super(player);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
