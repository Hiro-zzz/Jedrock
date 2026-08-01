package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * A player's health is about to change — <b>any</b> change, from any source: damage landing, a respawn
 * resetting it, {@code /heal}, a script's {@code setHealth}.
 *
 * <p>{@link PlayerDamageEvent} already covered one direction and one cause. Healing had nothing at all,
 * so a regeneration system, a health bar above a name tag or a "you are hurt" hint had no moment to hang
 * on and had to poll. This is the number itself changing, which is the fact all of those actually wanted.
 *
 * <p><b>Order against damage.</b> A hit fires {@code PlayerDamage} first — that is where the hit can be
 * vetoed or rescaled, and where the cause is known — and only then does the resulting number pass through
 * here. So a listener that wants "no fall damage" belongs on the damage event; this one is the last word
 * on what the bar ends up reading, whatever caused it.
 *
 * <p><b>Cancelling</b> leaves the health where it was. Beware of what that means on a lethal hit: the
 * player does not die, and does not heal either — they are simply still standing there on the health they
 * had. That is a real thing to want (an invulnerable boss NPC, a spectator) and a real way to make a
 * player unkillable by accident.
 *
 * <p>{@code setNewHealth(…)} rewrites the number instead, clamped to 0..max by the core afterwards.
 * Setting it to what it already is has the same effect as cancelling.
 */
public final class PlayerHealthChangeEvent extends CancellablePlayerEvent {

    private final int oldHealth;
    private int newHealth;

    public PlayerHealthChangeEvent(Player player, int oldHealth, int newHealth) {
        super(player);
        this.oldHealth = oldHealth;
        this.newHealth = newHealth;
    }

    /** What the player's health is right now, before this change. */
    public int getOldHealth() {
        return oldHealth;
    }

    /** What it is about to become. */
    public int getNewHealth() {
        return newHealth;
    }

    /** Rewrite the number this change will settle on. Clamped to {@code 0..getMaxHealth()} by the core. */
    public void setNewHealth(int newHealth) {
        this.newHealth = newHealth;
    }

    /** Whether this change takes health away — the direction, without having to compare two numbers. */
    public boolean isDamage() {
        return newHealth < oldHealth;
    }
}
