package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player starts or stops sprinting. <b>Cancellable</b>: cancelling makes the server ignore the
 * toggle — the pose isn't recorded and isn't relayed to other players. (Like sneaking, the sprinter's own
 * client still runs locally; the server can only refuse to reflect it.)
 */
public class PlayerToggleSprintEvent extends CancellablePlayerEvent {

    private final boolean sprinting;

    public PlayerToggleSprintEvent(Player player, boolean sprinting) {
        super(player);
        this.sprinting = sprinting;
    }

    /** {@code true} if the player is now sprinting, {@code false} if they stopped. */
    public boolean isSprinting() {
        return sprinting;
    }
}
