package com.jedrock.api.event.player;

import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.Player;

/**
 * Fired before a piece of a player's armor changes — dragged into an armor slot by a creative client, or
 * set from code through {@link Player#setArmor}. One event per slot, carrying what was worn and what is
 * about to be.
 *
 * <p><b>Cancellable</b>: cancelling leaves the slot as it was. Because a creative client has already drawn
 * the piece on itself optimistically, a refused change re-sends the real slot to that client, which puts
 * the old piece back — the same correction a refused block edit gets.
 *
 * <p>Armor is visual in this server: it dresses the avatar on every edition and protects from nothing, so
 * this event is about appearance, never about damage.
 */
public class PlayerArmorChangeEvent extends CancellablePlayerEvent {

    private final ArmorSlot slot;
    private final int previous;
    private final int next;

    public PlayerArmorChangeEvent(Player player, ArmorSlot slot, int previous, int next) {
        super(player);
        this.slot = slot;
        this.previous = previous;
        this.next = next;
    }

    /** Which piece is changing. */
    public ArmorSlot getSlot() {
        return slot;
    }

    /** The canonical state worn until now; 0 = the slot was empty. */
    public int getPrevious() {
        return previous;
    }

    /** The canonical state about to be worn; 0 = the piece is being taken off. */
    public int getNext() {
        return next;
    }
}
