package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player selects a different hotbar slot — the item in their hand is about to become a
 * different one. Reported by the client on every edition.
 *
 * <p><b>Cancellable</b>, with the same honest limit as the sneak toggle: cancelling makes the server
 * refuse to <em>reflect</em> the switch — the new slot isn't recorded, so nothing that reads the held item
 * (a chest deposit, a script, a late joiner) sees it, and the new item isn't drawn in the player's hand on
 * anyone else's screen. The switcher's own client still shows its own selection, because no edition here
 * has a clientbound packet that moves the hotbar cursor back. This is a client-authoritative model: the
 * server can decline to believe the client, not overrule it.
 *
 * <p>Fires only for a slot switch. When the stack <em>inside</em> the held slot changes — mined, placed,
 * or written by a script — the hand is redrawn without this event, because the player didn't choose it.
 */
public class PlayerHeldItemChangeEvent extends CancellablePlayerEvent {

    private final int previousSlot;
    private final int newSlot;
    private final int previousItem;
    private final int newItem;

    public PlayerHeldItemChangeEvent(Player player, int previousSlot, int newSlot,
                                     int previousItem, int newItem) {
        super(player);
        this.previousSlot = previousSlot;
        this.newSlot = newSlot;
        this.previousItem = previousItem;
        this.newItem = newItem;
    }

    /** The hotbar slot (0-8) selected until now. */
    public int getPreviousSlot() {
        return previousSlot;
    }

    /** The hotbar slot (0-8) being selected. */
    public int getNewSlot() {
        return newSlot;
    }

    /** The canonical state held until now; 0 = an empty hand. */
    public int getPreviousItem() {
        return previousItem;
    }

    /** The canonical state about to be held; 0 = an empty hand. */
    public int getNewItem() {
        return newItem;
    }
}
