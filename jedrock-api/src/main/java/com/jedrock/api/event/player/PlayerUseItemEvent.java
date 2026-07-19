package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player starts or stops using the held item — the eat / drink / block / draw-bow gesture.
 * <b>Cancellable</b>: cancelling makes the server ignore it, so the pose isn't recorded or relayed. The
 * third of the pose trio alongside {@link PlayerToggleSneakEvent} and {@link PlayerToggleSprintEvent}.
 *
 * <p>Like the others, this is a client-authoritative gesture: the user's own client plays it regardless;
 * the server only decides whether to reflect it to everyone else.
 */
public class PlayerUseItemEvent extends CancellablePlayerEvent {

    private final boolean using;

    public PlayerUseItemEvent(Player player, boolean using) {
        super(player);
        this.using = using;
    }

    /** {@code true} if the player started using an item, {@code false} if they stopped. */
    public boolean isUsing() {
        return using;
    }
}
