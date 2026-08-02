package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * A player is about to be shown a container — a chest they right-clicked, or a menu a script opened.
 *
 * <p>The right-click that leads to a chest already had {@link com.jedrock.api.event.block
 * .PlayerInteractBlockEvent}, and cancelling that has always stopped the chest coming up. This is the
 * other half of that: it fires for a menu too, it says what is being opened rather than which block was
 * clicked, and it is the one place both routes pass through — which is what a lock, a shop or an audit
 * log actually wants to hook.
 *
 * <p><b>Cancelling</b> means no window is shown and nothing is bound: the player is left standing in front
 * of a chest that did not open. Say something if you cancel, or it reads as a bug to whoever clicked.
 *
 * <p>On Bedrock, remember what a "window" is there: 1.1.5 will not raise a chest window at all and trades
 * through click-transfer instead, and a menu becomes a {@code /pick} list. This event fires for those too,
 * since it announces the container being opened rather than a packet being sent.
 */
public final class ContainerOpenEvent extends CancellablePlayerEvent {

    private final ContainerType type;
    private final String title;
    private final int x;
    private final int y;
    private final int z;
    private final int size;

    public ContainerOpenEvent(Player player, ContainerType type, String title,
                              int x, int y, int z, int size) {
        super(player);
        this.type = type;
        this.title = title;
        this.x = x;
        this.y = y;
        this.z = z;
        this.size = size;
    }

    /** Whether this is a world chest or a script's menu. */
    public ContainerType getType() {
        return type;
    }

    /** The title the window carries. For a chest, simply {@code "Chest"}. */
    public String getTitle() {
        return title;
    }

    /** The chest block's position. All zero for a {@link ContainerType#MENU}, which has no block. */
    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    /** How many slots the container has (27 for a chest). */
    public int getSize() {
        return size;
    }
}
