package com.jedrock.api.event.player;

import com.jedrock.api.item.Enchantment;
import com.jedrock.api.player.Player;

/**
 * Fired when an enchantment is about to be put on a stack — from {@code /enchant}, from a script, or from
 * anything else that enchants something. <b>Cancellable</b>: cancelling leaves the stack as it was.
 *
 * <p>The {@link #getLevel() level} is mutable, so a listener can cap or weaken an enchantment rather than
 * having to choose between allowing it whole and refusing it; a level of zero or less takes it off, which
 * is how removal is spelled everywhere else here too.
 *
 * <p>Not fired for a stack that arrives already enchanted from a custom item's definition — that is the
 * definition being what it is, not an act of enchanting.
 */
public class ItemEnchantEvent extends CancellablePlayerEvent {

    private final int slot;
    private final int state;
    private final Enchantment enchantment;
    private int level;

    public ItemEnchantEvent(Player player, int slot, int state, Enchantment enchantment, int level) {
        super(player);
        this.slot = slot;
        this.state = state;
        this.enchantment = enchantment;
        this.level = level;
    }

    /** The inventory slot holding the stack being enchanted. */
    public int getSlot() {
        return slot;
    }

    /** The canonical {@code (id << 4) | meta} state of that stack. */
    public int getState() {
        return state;
    }

    public Enchantment getEnchantment() {
        return enchantment;
    }

    /** The level being applied, as a person counts it: 1 is Sharpness I. */
    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}
