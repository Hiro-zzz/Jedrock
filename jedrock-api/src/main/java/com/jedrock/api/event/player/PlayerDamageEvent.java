package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a survival player is about to take damage, through the one path every source funnels into
 * (fall, void, PvP, {@code /kill}). <b>Cancellable</b>: cancelling deals no damage at all. The
 * {@link #getAmount() amount} (in half-hearts) is mutable, so a listener can soften or sharpen the hit —
 * setting it to zero or below is the same as cancelling.
 *
 * <p>Only fired in survival: a creative player takes no damage, so the event never reaches a listener there.
 */
public class PlayerDamageEvent extends CancellablePlayerEvent {

    private final DamageCause cause;
    private int amount;

    public PlayerDamageEvent(Player player, DamageCause cause, int amount) {
        super(player);
        this.cause = cause;
        this.amount = amount;
    }

    /** What is dealing the damage. */
    public DamageCause getCause() {
        return cause;
    }

    /** The damage in half-hearts (2 = one heart). */
    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }
}
