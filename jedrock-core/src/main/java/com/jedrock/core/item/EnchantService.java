package com.jedrock.core.item;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.ItemEnchantEvent;
import com.jedrock.api.item.Enchantment;
import com.jedrock.api.item.Enchantments;
import com.jedrock.core.player.CorePlayer;

/**
 * Putting an enchantment on a stack — the one path, so the event fires once and the client is refreshed
 * once however it was asked for.
 *
 * <p>There is deliberately no <em>enchanting</em> here in the vanilla sense: no table, no experience, no
 * randomness. Enchantments are given — by {@code /enchant}, by a script, or by a custom item's definition
 * — because a table would need levels this server doesn't keep and a window the 1.1.5 client cannot raise.
 *
 * <p>What an enchantment then <em>does</em> lives where the decision it changes already lives: melee
 * damage in {@code CombatService}, damage taken in the same place, and a mined block's drop in
 * {@code ConnectionBridge}. This class only decides what a stack carries.
 */
public final class EnchantService {

    private final EventBus events;

    public EnchantService(EventBus events) {
        this.events = events;
    }

    /**
     * Enchant the stack in {@code slot} of a player's inventory. A level of zero or less takes the
     * enchantment off. Fires {@link ItemEnchantEvent}, which may refuse it or change the level.
     *
     * @return {@code true} if the stack changed
     */
    public boolean enchant(CorePlayer player, int slot, Enchantment enchantment, int level) {
        if (player == null || enchantment == null || slot < 0 || slot >= CorePlayer.INV_SLOTS) {
            return false;
        }
        var inventory = player.getInventory();
        if (inventory.isEmpty(slot)) {
            return false;   // an enchantment belongs to a stack, and there is no stack here
        }
        int applied = level;
        if (events.hasListeners(ItemEnchantEvent.class)) {
            ItemEnchantEvent event = events.post(new ItemEnchantEvent(
                    player, slot, inventory.stateAt(slot), enchantment, level));
            if (event.isCancelled()) {
                return false;
            }
            applied = event.getLevel();
        }
        Enchantments before = inventory.enchantmentsAt(slot);
        Enchantments after = before.with(enchantment, applied);
        if (after.equals(before)) {
            return false;
        }
        inventory.setEnchantments(slot, after);
        player.syncSlot(slot);   // the glint and the tooltip are the whole visible feature
        return true;
    }

    /** Enchant whatever the player is holding. */
    public boolean enchantHeld(CorePlayer player, Enchantment enchantment, int level) {
        return enchant(player, player.getHeldItemSlot(), enchantment, level);
    }

    /** Strip every enchantment from the stack in {@code slot}. @return whether it had any. */
    public boolean disenchant(CorePlayer player, int slot) {
        if (player == null || slot < 0 || slot >= CorePlayer.INV_SLOTS) {
            return false;
        }
        var inventory = player.getInventory();
        if (inventory.enchantmentsAt(slot).isEmpty()) {
            return false;
        }
        inventory.setEnchantments(slot, Enchantments.NONE);
        player.syncSlot(slot);
        return true;
    }
}
