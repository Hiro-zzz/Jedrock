package com.jedrock.api.event.player;

/**
 * Fired when a player clicks a slot in their own (survival) inventory, before the click is applied.
 * <b>Cancellable</b>: cancelling refuses the click — the server re-syncs the inventory so the client reverts.
 * Creative inventories are client-managed and never raise this.
 *
 * <p>{@link #getSlot()} is the core inventory index (0-8 hotbar, 9-35 main, 36-39 armor, 40 off-hand).
 * {@link #getButton()} is 0 (left) or 1 (right); {@link #isShift()} is a shift/quick-move click.
 */
public class InventoryClickEvent extends CancellablePlayerEvent {

    private final int slot;
    private final int button;
    private final boolean shift;

    public InventoryClickEvent(com.jedrock.api.player.Player player, int slot, int button, boolean shift) {
        super(player);
        this.slot = slot;
        this.button = button;
        this.shift = shift;
    }

    /** Core inventory index clicked (0-8 hotbar, 9-35 main, 36-39 armor, 40 off-hand). */
    public int getSlot() {
        return slot;
    }

    /** 0 = left click, 1 = right click. */
    public int getButton() {
        return button;
    }

    public boolean isRightClick() {
        return button == 1;
    }

    /** Whether this was a shift/quick-move click (moves the stack between regions). */
    public boolean isShift() {
        return shift;
    }
}
