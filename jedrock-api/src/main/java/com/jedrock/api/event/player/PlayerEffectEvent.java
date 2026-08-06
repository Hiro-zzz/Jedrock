package com.jedrock.api.event.player;

import com.jedrock.api.entity.Effect;
import com.jedrock.api.player.Player;

/**
 * Fired when a player is about to be put under a status effect — from {@code /effect}, from a script, or
 * from anything else that hands one out. <b>Cancellable</b>: cancelling means the effect never lands and
 * the client is never told about it.
 *
 * <p>The {@link #getAmplifier() level} and the {@link #getDurationSeconds() duration} are both mutable, so
 * a listener can weaken, strengthen or shorten an effect rather than having to choose between allowing it
 * whole and refusing it — a region that halves potions is a listener, not a special case in the core.
 *
 * <p>Not fired when an effect is <em>removed</em> or expires. Removal is the server tidying up after a
 * decision that has already been made here.
 */
public class PlayerEffectEvent extends CancellablePlayerEvent {

    private final Effect effect;
    private int amplifier;
    private int durationSeconds;
    private final boolean reapplied;

    public PlayerEffectEvent(Player player, Effect effect, int amplifier, int durationSeconds,
                             boolean reapplied) {
        super(player);
        this.effect = effect;
        this.amplifier = amplifier;
        this.durationSeconds = durationSeconds;
        this.reapplied = reapplied;
    }

    /** Which effect. */
    public Effect getEffect() {
        return effect;
    }

    /** The level minus one, as every edition's wire counts it: 0 is "Speed I". */
    public int getAmplifier() {
        return amplifier;
    }

    public void setAmplifier(int amplifier) {
        this.amplifier = amplifier;
    }

    /** How long it is meant to last. */
    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /** Whether the player was already under this effect and it is being refreshed rather than given. */
    public boolean isReapplied() {
        return reapplied;
    }
}
