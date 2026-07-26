package com.jedrock.core.inventory;

import com.jedrock.core.player.CorePlayer;

/**
 * The click callback of a <b>virtual menu</b> — a script-owned chest window that behaves as a set of
 * buttons rather than storage. When a menu carries one of these, its slots are read-only (the server
 * never moves items in or out of them) and every click on a menu slot calls this instead, so a script can
 * treat each slot as a button.
 */
@FunctionalInterface
public interface MenuClick {

    /**
     * A player clicked slot {@code slot} of the menu, which holds the canonical state {@code state}
     * (0 = empty). The item does not move — the menu is redrawn as it was — so this is purely a signal.
     */
    void onClick(CorePlayer player, int slot, int state);
}
